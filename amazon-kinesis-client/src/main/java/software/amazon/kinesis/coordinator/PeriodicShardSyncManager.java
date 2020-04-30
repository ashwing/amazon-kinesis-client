/*
 * Copyright 2019 Amazon.com, Inc. or its affiliates.
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
package software.amazon.kinesis.coordinator;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.Validate;
import software.amazon.awssdk.utils.CollectionUtils;
import software.amazon.kinesis.common.StreamConfig;
import software.amazon.kinesis.common.StreamIdentifier;
import software.amazon.kinesis.exceptions.internal.KinesisClientLibIOException;
import software.amazon.kinesis.leases.Lease;
import software.amazon.kinesis.leases.LeaseRefresher;
import software.amazon.kinesis.leases.MultiStreamLease;
import software.amazon.kinesis.leases.ShardSyncTaskManager;
import software.amazon.kinesis.leases.exceptions.DependencyException;
import software.amazon.kinesis.leases.exceptions.InvalidStateException;
import software.amazon.kinesis.leases.exceptions.ProvisionedThroughputException;
import software.amazon.kinesis.lifecycle.ConsumerTask;
import software.amazon.kinesis.lifecycle.TaskResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The top level orchestrator for coordinating the periodic shard sync related
 * activities.
 */
@Getter
@EqualsAndHashCode
@Slf4j
class PeriodicShardSyncManager {
    private static final long INITIAL_DELAY = 60 * 1000L;
    private static final long PERIODIC_SHARD_SYNC_INTERVAL_MILLIS = 5 * 60 * 1000L;

    private final String workerId;
    private final LeaderDecider leaderDecider;
    private final LeaseRefresher leaseRefresher;
    private final Map<StreamIdentifier, StreamConfig> currentStreamConfigMap;
    private final Function<StreamConfig, ShardSyncTaskManager> shardSyncTaskManagerProvider;
    private final ScheduledExecutorService shardSyncThreadPool;
    private final boolean isMultiStreamingMode;
    private boolean isRunning;

    PeriodicShardSyncManager(String workerId, LeaderDecider leaderDecider, LeaseRefresher leaseRefresher,
            Map<StreamIdentifier, StreamConfig> currentStreamConfigMap,
            Function<StreamConfig, ShardSyncTaskManager> shardSyncTaskManagerProvider, boolean isMultiStreamingMode) {
        this(workerId, leaderDecider, leaseRefresher, currentStreamConfigMap, shardSyncTaskManagerProvider,
                Executors.newSingleThreadScheduledExecutor(), isMultiStreamingMode);
    }

    PeriodicShardSyncManager(String workerId, LeaderDecider leaderDecider, LeaseRefresher leaseRefresher,
            Map<StreamIdentifier, StreamConfig> currentStreamConfigMap,
            Function<StreamConfig, ShardSyncTaskManager> shardSyncTaskManagerProvider,
            ScheduledExecutorService shardSyncThreadPool, boolean isMultiStreamingMode) {
        Validate.notBlank(workerId, "WorkerID is required to initialize PeriodicShardSyncManager.");
        Validate.notNull(leaderDecider, "LeaderDecider is required to initialize PeriodicShardSyncManager.");
        this.workerId = workerId;
        this.leaderDecider = leaderDecider;
        this.leaseRefresher = leaseRefresher;
        this.currentStreamConfigMap = currentStreamConfigMap;
        this.shardSyncTaskManagerProvider = shardSyncTaskManagerProvider;
        this.shardSyncThreadPool = shardSyncThreadPool;
        this.isMultiStreamingMode = isMultiStreamingMode;
    }

    public synchronized TaskResult start() {
        if (!isRunning) {
            final Runnable periodicShardSyncer = () -> {
                try {
                    runShardSync();
                } catch (Throwable t) {
                    log.error("Error during runShardSync.", t);
                }
            };
            shardSyncThreadPool.scheduleWithFixedDelay(periodicShardSyncer, INITIAL_DELAY, PERIODIC_SHARD_SYNC_INTERVAL_MILLIS,
                    TimeUnit.MILLISECONDS);
            isRunning = true;

        }
        return new TaskResult(null);
    }

    /**
     * Runs shardSync once
     * Does not schedule periodic shardSync
     * @return the result of the task
     */
    public synchronized void syncShardsOnce() throws Exception {
        // TODO: Resume the shard sync from failed stream in the next attempt, to avoid syncing
        // TODO: for already synced streams
        for(Map.Entry<StreamIdentifier, StreamConfig> streamConfigEntry : currentStreamConfigMap.entrySet()) {
            final StreamIdentifier streamIdentifier = streamConfigEntry.getKey();
            log.info("Syncing Kinesis shard info for " + streamIdentifier);
            final StreamConfig streamConfig = streamConfigEntry.getValue();
            final ShardSyncTaskManager shardSyncTaskManager = shardSyncTaskManagerProvider.apply(streamConfig);
            final TaskResult taskResult = shardSyncTaskManager.executeShardSyncTask();
            if (taskResult.getException() != null) {
                throw taskResult.getException();
            }
        }
    }

    public void stop() {
        if (isRunning) {
            log.info(String.format("Shutting down leader decider on worker %s", workerId));
            leaderDecider.shutdown();
            log.info(String.format("Shutting down periodic shard sync task scheduler on worker %s", workerId));
            shardSyncThreadPool.shutdown();
            isRunning = false;
        }
    }

    private void runShardSync() {
        if (leaderDecider.isLeader(workerId)) {
            try {
                final Map<StreamIdentifier, List<Lease>> streamToLeasesMap = getStreamToLeasesMap(currentStreamConfigMap.keySet());
                for (Map.Entry<StreamIdentifier, StreamConfig> streamConfigEntry : currentStreamConfigMap.entrySet()) {
                    final ShardSyncTaskManager shardSyncTaskManager = shardSyncTaskManagerProvider.apply(streamConfigEntry.getValue());
                    if (!shardSyncTaskManager.syncShardAndLeaseInfo()) {
                        log.warn(
                                "Failed to submit shard sync task for stream {}. This could be due to the previous shard sync task not finished.",
                                shardSyncTaskManager.shardDetector().streamIdentifier().streamName());
                    }
                }
            } catch (Exception e) {
                // TODO : Log
            }
        } else {
            log.debug(String.format("WorkerId %s is not a leader, not running the shard sync task", workerId));
        }
    }

    private Map<StreamIdentifier, List<Lease>> getStreamToLeasesMap(final Set<StreamIdentifier> streamIdentifiersToFilter)
            throws DependencyException, ProvisionedThroughputException, InvalidStateException {
        final List<Lease> leases = leaseRefresher.listLeases();
        if(!isMultiStreamingMode) {
            Validate.isTrue(streamIdentifiersToFilter.size() == 1);
            return Collections.singletonMap(streamIdentifiersToFilter.iterator().next(), leases);
        } else {
            final Map<StreamIdentifier, List<Lease>> streamToLeasesMap = new HashMap<>();
            for(Lease lease : leases) {
                StreamIdentifier streamIdentifier = StreamIdentifier
                        .multiStreamInstance(((MultiStreamLease) lease).streamIdentifier());
                if(streamIdentifiersToFilter.contains(streamIdentifier)) {
                    streamToLeasesMap.computeIfAbsent(streamIdentifier, s -> new ArrayList<>()).add(lease);
                }
            }
            return streamToLeasesMap;
        }
    }

    /**
     * Checks if the entire hash range is covered
     * @return true if covered, false otherwise
     */
    public boolean isHashRangeComplete(List<Lease> leases) {
        if(CollectionUtils.isNullOrEmpty(leases)) {
            return false;
        } else {
//            leases.stream().filter(lease -> lease.checkpoint().isShardEnd())
            return false;
        }
    }
}
