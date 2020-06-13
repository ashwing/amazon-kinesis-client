/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates.
 * Licensed under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package software.amazon.kinesis.leases;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient;
import software.amazon.awssdk.services.kinesis.model.GetRecordsRequest;
import software.amazon.awssdk.services.kinesis.model.GetRecordsResponse;
import software.amazon.awssdk.services.kinesis.model.GetShardIteratorRequest;
import software.amazon.awssdk.services.kinesis.model.GetShardIteratorResponse;
import software.amazon.awssdk.services.kinesis.model.ResourceNotFoundException;
import software.amazon.awssdk.services.kinesis.model.ShardIteratorType;
import software.amazon.awssdk.utils.CollectionUtils;
import software.amazon.kinesis.common.FutureUtils;
import software.amazon.kinesis.common.KinesisRequestsBuilder;
import software.amazon.kinesis.common.StreamIdentifier;
import software.amazon.kinesis.leases.exceptions.DependencyException;
import software.amazon.kinesis.leases.exceptions.LeasePendingDeletion;
import software.amazon.kinesis.leases.exceptions.InvalidStateException;
import software.amazon.kinesis.leases.exceptions.ProvisionedThroughputException;
import software.amazon.kinesis.metrics.MetricsFactory;
import software.amazon.kinesis.retrieval.AWSExceptionManager;
import software.amazon.kinesis.retrieval.kpl.ExtendedSequenceNumber;

import java.time.Duration;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Helper class to cleanup of any expired/closed shard leases. It will cleanup leases periodically as defined by
 * {@link LeaseManagementConfig#leaseCleanupConfig()} asynchronously.
 */
@Accessors(fluent=true)
@Slf4j
@RequiredArgsConstructor
@EqualsAndHashCode
public class LeaseCleanupManager {
    @NonNull
    private final LeaseCoordinator leaseCoordinator;
    @NonNull
    private final KinesisAsyncClient kinesisClient;
    @NonNull
    private final MetricsFactory metricsFactory;
    @NonNull
    private final Duration maxFutureWait;
    @NonNull
    private final ScheduledExecutorService deletionThreadPool;
    private final boolean cleanupLeasesUponShardCompletion;
    private final long leaseCleanupIntervalMillis;
    private final long completedLeaseCleanupIntervalMillis;
    private final long garbageLeaseCleanupIntervalMillis;
    private final Stopwatch completedLeaseStopwatch = Stopwatch.createUnstarted();
    private final Stopwatch garbageLeaseStopwatch = Stopwatch.createUnstarted();

    private final Queue<LeasePendingDeletion> deletionQueue = new ConcurrentLinkedQueue<>();

    private static final int MAX_RECORDS = 1;
    private static final long INITIAL_DELAY = 0L;

    @Getter
    private volatile boolean isRunning = false;

    /**
     * Starts the lease cleanup thread, which is scheduled periodically as specified by
     * {@link LeaseCleanupManager#leaseCleanupIntervalMillis}
     */
    public void start() {
        log.debug("Starting lease cleanup thread.");
        completedLeaseStopwatch.start();
        garbageLeaseStopwatch.start();

        deletionThreadPool.scheduleAtFixedRate(new LeaseCleanupThread(), INITIAL_DELAY, leaseCleanupIntervalMillis,
                TimeUnit.MILLISECONDS);
    }

    /**
     * Enqueues a lease for deletion.
     * @param leasePendingDeletion
     */
    public void enqueueForDeletion(LeasePendingDeletion leasePendingDeletion) {
        final Lease lease = leasePendingDeletion.lease();
        if (lease == null) {
            log.warn("Cannot enqueue lease {} for deferred deletion - instance doesn't hold the lease for that shard.",
                    lease.leaseKey());
        } else {
            //TODO: Optimize verifying duplicate entries https://sim.amazon.com/issues/KinesisLTR-597.
            if (!deletionQueue.contains(leasePendingDeletion)) {
                log.debug("Enqueuing lease {} for deferred deletion.", lease.leaseKey());
                deletionQueue.add(leasePendingDeletion);
            } else {
                log.warn("Lease {} is already pending deletion, not enqueueing for deletion.", lease.leaseKey());
            }
        }
    }

    /**
     * Returns how many leases are currently waiting in the queue pending deletion.
     * @return number of leases pending deletion.
     */
    public int leasesPendingDeletion() {
        return deletionQueue.size();
    }

    private boolean timeToCheckForCompletedShard() {
        return completedLeaseStopwatch.elapsed(TimeUnit.MILLISECONDS) >= completedLeaseCleanupIntervalMillis;
    }

    private boolean timeToCheckForGarbageShard() {
        return garbageLeaseStopwatch.elapsed(TimeUnit.MILLISECONDS) >= garbageLeaseCleanupIntervalMillis;
    }

    private LeaseCleanupResult cleanupLease(LeasePendingDeletion leasePendingDeletion) throws TimeoutException,
            InterruptedException, DependencyException, ProvisionedThroughputException, InvalidStateException {
        final Lease lease = leasePendingDeletion.lease();
        final ShardInfo shardInfo = leasePendingDeletion.shardInfo();
        final StreamIdentifier streamIdentifier = leasePendingDeletion.streamIdentifier();

        final AWSExceptionManager exceptionManager = createExceptionManager();

        boolean cleanedUpCompletedLease = false;
        boolean cleanedUpGarbageLease = false;
        boolean alreadyCheckedForGarbageCollection = false;

        try {
            if (cleanupLeasesUponShardCompletion && timeToCheckForCompletedShard()) {
                Set<String> childShardKeys = leaseCoordinator.leaseRefresher().getLease(lease.leaseKey()).childShardIds();
                if (CollectionUtils.isNullOrEmpty(childShardKeys)) {
                    try {
                        childShardKeys = getChildShardsFromService(shardInfo, streamIdentifier);

                        if (childShardKeys == null) {
                            log.error("No child shards returned from service for shard {} for {}.", shardInfo.shardId(), streamIdentifier.streamName());
                        } else {
                            updateLeaseWithChildShards(leasePendingDeletion, childShardKeys);
                        }
                    } catch (ExecutionException e) {
                        throw exceptionManager.apply(e.getCause());
                    } finally {
                        alreadyCheckedForGarbageCollection = true;
                    }
                }
                cleanedUpCompletedLease = cleanupLeaseForCompletedShard(lease, shardInfo, childShardKeys);
            }

            if (!alreadyCheckedForGarbageCollection && timeToCheckForGarbageShard()) {
                try {
                    getChildShardsFromService(shardInfo, streamIdentifier);
                } catch (ExecutionException e) {
                    throw exceptionManager.apply(e.getCause());
                }
            }
        } catch (ResourceNotFoundException e) {
            cleanedUpGarbageLease = cleanupLeaseForGarbageShard(lease);
        }

        return new LeaseCleanupResult(cleanedUpCompletedLease, cleanedUpGarbageLease);
    }

    private Set<String> getChildShardsFromService(ShardInfo shardInfo, StreamIdentifier streamIdentifier)
            throws InterruptedException, ExecutionException, TimeoutException {
        final GetShardIteratorRequest getShardIteratorRequest = KinesisRequestsBuilder.getShardIteratorRequestBuilder()
                .streamName(streamIdentifier.streamName())
                .shardIteratorType(ShardIteratorType.LATEST)
                .shardId(shardInfo.shardId())
                .build();

        final GetShardIteratorResponse getShardIteratorResponse =
                FutureUtils.resolveOrCancelFuture(kinesisClient.getShardIterator(getShardIteratorRequest), maxFutureWait);

        final GetRecordsRequest getRecordsRequest = KinesisRequestsBuilder.getRecordsRequestBuilder()
                .shardIterator(getShardIteratorResponse.shardIterator())
                .limit(MAX_RECORDS)
                .build();

        final GetRecordsResponse getRecordsResponse =
                FutureUtils.resolveOrCancelFuture(kinesisClient.getRecords(getRecordsRequest), maxFutureWait);

        return getRecordsResponse.childShards().stream().map(c -> c.shardId()).collect(Collectors.toSet());
    }


    // A lease that ended with SHARD_END from ResourceNotFoundException is safe to delete if it no longer exists in the
    // stream (known explicitly from ResourceNotFound being thrown when processing this shard),
    private boolean cleanupLeaseForGarbageShard(Lease lease) throws DependencyException, ProvisionedThroughputException, InvalidStateException {
        log.info("Deleting lease {} as it is not present in the stream.", lease);
        leaseCoordinator.leaseRefresher().deleteLease(lease);
        return true;
    }

    private boolean allParentShardLeasesDeleted(Lease lease, ShardInfo shardInfo) throws DependencyException, ProvisionedThroughputException, InvalidStateException {
        for (String parentShard : lease.parentShardIds()) {
            final Lease parentLease = leaseCoordinator.leaseRefresher().getLease(ShardInfo.getLeaseKey(shardInfo, parentShard));

            if (parentLease != null) {
                log.warn("Lease {} has a parent lease {} which is still present in the lease table, skipping deletion " +
                        "for this lease.", lease, parentLease);
                return false;
            }
        }
        return true;
    }

    // We should only be deleting the current shard's lease if
    // 1. All of its children are currently being processed, i.e their checkpoint is not TRIM_HORIZON or AT_TIMESTAMP.
    // 2. Its parent shard lease(s) have already been deleted.
    private boolean cleanupLeaseForCompletedShard(Lease lease, ShardInfo shardInfo, Set<String> childShardKeys)
            throws DependencyException, ProvisionedThroughputException, InvalidStateException, IllegalStateException {
        final Set<String> processedChildShardLeases = new HashSet<>();

        for (String childShardKey : childShardKeys) {
            final Lease childShardLease = Optional.ofNullable(leaseCoordinator.leaseRefresher().getLease(childShardKey)).orElseThrow(
                    () -> new IllegalStateException("Child lease " + childShardKey + " for completed shard not found in " +
                            "lease table - not cleaning up lease " + lease));

            if (!childShardLease.checkpoint().equals(ExtendedSequenceNumber.TRIM_HORIZON)
                    && !childShardLease.checkpoint().equals(ExtendedSequenceNumber.AT_TIMESTAMP)) {
                processedChildShardLeases.add(childShardLease.leaseKey());
            }
        }

        if (!allParentShardLeasesDeleted(lease, shardInfo) || !Objects.equals(childShardKeys, processedChildShardLeases)) {
            return false;
        }

        log.info("Deleting lease {} as it has been completely processed and processing of child shard(s) has begun.",
                lease);
        leaseCoordinator.leaseRefresher().deleteLease(lease);

        return true;
    }

    private void updateLeaseWithChildShards(LeasePendingDeletion leasePendingDeletion, Set<String> childShardKeys)
            throws DependencyException, ProvisionedThroughputException, InvalidStateException {
        final Lease updatedLease = leasePendingDeletion.lease();
        updatedLease.childShardIds(childShardKeys);

        leaseCoordinator.leaseRefresher().updateLeaseWithMetaInfo(updatedLease, UpdateField.CHILD_SHARDS);
    }

    private AWSExceptionManager createExceptionManager() {
        final AWSExceptionManager exceptionManager = new AWSExceptionManager();
        exceptionManager.add(ResourceNotFoundException.class, t -> t);

        return exceptionManager;
    }

    @VisibleForTesting
    void cleanupLeases() {
        if (deletionQueue.isEmpty()) {
            log.debug("No leases pending deletion.");
        } else if (timeToCheckForCompletedShard() | timeToCheckForGarbageShard()) {
            final Queue<LeasePendingDeletion> failedDeletions = new ConcurrentLinkedQueue<>();
            boolean completedLeaseCleanedUp = false;
            boolean garbageLeaseCleanedUp = false;

            log.debug("Attempting to clean up {} lease(s).", deletionQueue.size());

            while (!deletionQueue.isEmpty()) {
                final LeasePendingDeletion leasePendingDeletion = deletionQueue.poll();
                final String leaseKey = leasePendingDeletion.lease().leaseKey();
                final StreamIdentifier streamIdentifier = leasePendingDeletion.streamIdentifier();
                boolean deletionFailed = true;
                try {
                    final LeaseCleanupResult leaseCleanupResult = cleanupLease(leasePendingDeletion);
                    completedLeaseCleanedUp |= leaseCleanupResult.cleanedUpCompletedLease();
                    garbageLeaseCleanedUp |= leaseCleanupResult.cleanedUpGarbageLease();

                    if (leaseCleanupResult.leaseCleanedUp()) {
                        log.debug("Successfully cleaned up lease {} for {}", leaseKey, streamIdentifier);
                        deletionFailed = false;
                    }
                } catch (Exception e) {
                    log.error("Failed to cleanup lease {} for {}. Will re-enqueue for deletion and retry on next " +
                            "scheduled execution.", leaseKey, streamIdentifier, e);
                }

                if (deletionFailed) {
                    log.debug("Did not cleanup lease {} for {}. Re-enqueueing for deletion.", leaseKey, streamIdentifier);
                    failedDeletions.add(leasePendingDeletion);
                }
            }

            if (completedLeaseCleanedUp) {
                log.debug("At least one completed lease was cleaned up - restarting interval");
                completedLeaseStopwatch.reset().start();
            }

            if (garbageLeaseCleanedUp) {
                log.debug("At least one garbage lease was cleaned up - restarting interval");
                garbageLeaseStopwatch.reset().start();
            }

            deletionQueue.addAll(failedDeletions);
        }
    }

    private class LeaseCleanupThread implements Runnable {
        @Override
        public void run() {
            cleanupLeases();
        }
    }

    @Value
    private class LeaseCleanupResult {
        boolean cleanedUpCompletedLease;
        boolean cleanedUpGarbageLease;

        public boolean leaseCleanedUp() {
            return cleanedUpCompletedLease | cleanedUpGarbageLease;
        }
    }
}