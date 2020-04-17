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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.same;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.internal.verification.VerificationModeFactory.atMost;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import com.google.common.base.Joiner;
import com.google.common.collect.Sets;
import io.reactivex.plugins.RxJavaPlugins;
import lombok.RequiredArgsConstructor;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient;
import software.amazon.awssdk.utils.Either;
import software.amazon.awssdk.utils.Validate;
import software.amazon.kinesis.checkpoint.Checkpoint;
import software.amazon.kinesis.checkpoint.CheckpointConfig;
import software.amazon.kinesis.checkpoint.CheckpointFactory;
import software.amazon.kinesis.common.InitialPositionInStream;
import software.amazon.kinesis.common.InitialPositionInStreamExtended;
import software.amazon.kinesis.common.StreamConfig;
import software.amazon.kinesis.common.StreamIdentifier;
import software.amazon.kinesis.exceptions.KinesisClientLibException;
import software.amazon.kinesis.exceptions.KinesisClientLibNonRetryableException;
import software.amazon.kinesis.leases.LeaseCoordinator;
import software.amazon.kinesis.leases.LeaseManagementConfig;
import software.amazon.kinesis.leases.LeaseManagementFactory;
import software.amazon.kinesis.leases.LeaseRefresher;
import software.amazon.kinesis.leases.ShardDetector;
import software.amazon.kinesis.leases.ShardInfo;
import software.amazon.kinesis.leases.ShardSyncTaskManager;
import software.amazon.kinesis.leases.dynamodb.DynamoDBLeaseRefresher;
import software.amazon.kinesis.leases.exceptions.DependencyException;
import software.amazon.kinesis.leases.exceptions.InvalidStateException;
import software.amazon.kinesis.leases.exceptions.ProvisionedThroughputException;
import software.amazon.kinesis.lifecycle.LifecycleConfig;
import software.amazon.kinesis.lifecycle.ShardConsumer;
import software.amazon.kinesis.lifecycle.events.InitializationInput;
import software.amazon.kinesis.lifecycle.events.LeaseLostInput;
import software.amazon.kinesis.lifecycle.events.ProcessRecordsInput;
import software.amazon.kinesis.lifecycle.events.ShardEndedInput;
import software.amazon.kinesis.lifecycle.events.ShutdownRequestedInput;
import software.amazon.kinesis.metrics.MetricsFactory;
import software.amazon.kinesis.metrics.MetricsConfig;
import software.amazon.kinesis.processor.Checkpointer;
import software.amazon.kinesis.processor.MultiStreamTracker;
import software.amazon.kinesis.processor.ProcessorConfig;
import software.amazon.kinesis.processor.ShardRecordProcessorFactory;
import software.amazon.kinesis.processor.ShardRecordProcessor;
import software.amazon.kinesis.retrieval.RecordsPublisher;
import software.amazon.kinesis.retrieval.RetrievalConfig;
import software.amazon.kinesis.retrieval.RetrievalFactory;
import software.amazon.kinesis.retrieval.kpl.ExtendedSequenceNumber;

/**
 *
 */
@RunWith(MockitoJUnitRunner.class)
public class SchedulerTest {
    private final String tableName = "tableName";
    private final String workerIdentifier = "workerIdentifier";
    private final String applicationName = "applicationName";
    private final String streamName = "streamName";
    private final String namespace = "testNamespace";

    private Scheduler scheduler;
    private ShardRecordProcessorFactory shardRecordProcessorFactory;
    private CheckpointConfig checkpointConfig;
    private CoordinatorConfig coordinatorConfig;
    private LeaseManagementConfig leaseManagementConfig;
    private LifecycleConfig lifecycleConfig;
    private MetricsConfig metricsConfig;
    private ProcessorConfig processorConfig;
    private RetrievalConfig retrievalConfig;

    @Mock
    private KinesisAsyncClient kinesisClient;
    @Mock
    private DynamoDbAsyncClient dynamoDBClient;
    @Mock
    private CloudWatchAsyncClient cloudWatchClient;
    @Mock
    private RetrievalFactory retrievalFactory;
    @Mock
    private RecordsPublisher recordsPublisher;
    @Mock
    private LeaseCoordinator leaseCoordinator;
    @Mock
    private ShardSyncTaskManager shardSyncTaskManager;
    @Mock
    private DynamoDBLeaseRefresher dynamoDBLeaseRefresher;
    @Mock
    private ShardDetector shardDetector;
    @Mock
    private Checkpointer checkpoint;
    @Mock
    private WorkerStateChangeListener workerStateChangeListener;
    @Mock
    private MultiStreamTracker multiStreamTracker;

    private Map<StreamIdentifier, ShardSyncTaskManager> shardSyncTaskManagerMap = new HashMap<>();
    private Map<StreamIdentifier, ShardDetector> shardDetectorMap = new HashMap<>();

    @Before
    public void setup() {
        shardRecordProcessorFactory = new TestShardRecordProcessorFactory();

        checkpointConfig = new CheckpointConfig().checkpointFactory(new TestKinesisCheckpointFactory());
        coordinatorConfig = new CoordinatorConfig(applicationName).parentShardPollIntervalMillis(100L).workerStateChangeListener(workerStateChangeListener);
        leaseManagementConfig = new LeaseManagementConfig(tableName, dynamoDBClient, kinesisClient, streamName,
                workerIdentifier).leaseManagementFactory(new TestKinesisLeaseManagementFactory(false, false));
        lifecycleConfig = new LifecycleConfig();
        metricsConfig = new MetricsConfig(cloudWatchClient, namespace);
        processorConfig = new ProcessorConfig(shardRecordProcessorFactory);
        retrievalConfig = new RetrievalConfig(kinesisClient, streamName, applicationName)
                .retrievalFactory(retrievalFactory);

        final List<StreamConfig> streamConfigList = new ArrayList<StreamConfig>() {{
            add(new StreamConfig(StreamIdentifier.multiStreamInstance("acc1:stream1:1"), InitialPositionInStreamExtended.newInitialPosition(
                    InitialPositionInStream.LATEST)));
            add(new StreamConfig(StreamIdentifier.multiStreamInstance("acc1:stream2:2"), InitialPositionInStreamExtended.newInitialPosition(
                    InitialPositionInStream.LATEST)));
            add(new StreamConfig(StreamIdentifier.multiStreamInstance("acc2:stream1:1"), InitialPositionInStreamExtended.newInitialPosition(
                    InitialPositionInStream.LATEST)));
            add(new StreamConfig(StreamIdentifier.multiStreamInstance("acc2:stream2:3"), InitialPositionInStreamExtended.newInitialPosition(
                    InitialPositionInStream.LATEST)));
        }};

        when(multiStreamTracker.streamConfigList()).thenReturn(streamConfigList);
        when(leaseCoordinator.leaseRefresher()).thenReturn(dynamoDBLeaseRefresher);
        when(shardSyncTaskManager.shardDetector()).thenReturn(shardDetector);
        when(retrievalFactory.createGetRecordsCache(any(ShardInfo.class), any(MetricsFactory.class))).thenReturn(recordsPublisher);
        when(shardDetector.streamIdentifier()).thenReturn(mock(StreamIdentifier.class));

        scheduler = new Scheduler(checkpointConfig, coordinatorConfig, leaseManagementConfig, lifecycleConfig,
                metricsConfig, processorConfig, retrievalConfig);
    }

    /**
     * Test method for {@link Scheduler#applicationName()}.
     */
    @Test
    public void testGetStageName() {
        final String stageName = "testStageName";
        coordinatorConfig = new CoordinatorConfig(stageName);
        scheduler = new Scheduler(checkpointConfig, coordinatorConfig, leaseManagementConfig, lifecycleConfig,
                metricsConfig, processorConfig, retrievalConfig);
        assertEquals(stageName, scheduler.applicationName());
    }

    @Test
    public final void testCreateOrGetShardConsumer() {
        final String shardId = "shardId-000000000000";
        final String concurrencyToken = "concurrencyToken";
        final ShardInfo shardInfo = new ShardInfo(shardId, concurrencyToken, null, ExtendedSequenceNumber.TRIM_HORIZON);
        final ShardConsumer shardConsumer1 = scheduler.createOrGetShardConsumer(shardInfo, shardRecordProcessorFactory);
        assertNotNull(shardConsumer1);
        final ShardConsumer shardConsumer2 = scheduler.createOrGetShardConsumer(shardInfo, shardRecordProcessorFactory);
        assertNotNull(shardConsumer2);

        assertSame(shardConsumer1, shardConsumer2);

        final String anotherConcurrencyToken = "anotherConcurrencyToken";
        final ShardInfo shardInfo2 = new ShardInfo(shardId, anotherConcurrencyToken, null,
                ExtendedSequenceNumber.TRIM_HORIZON);
        final ShardConsumer shardConsumer3 = scheduler.createOrGetShardConsumer(shardInfo2, shardRecordProcessorFactory);
        assertNotNull(shardConsumer3);

        assertNotSame(shardConsumer1, shardConsumer3);
    }

    // TODO: figure out the behavior of the test.
    @Test
    public void testWorkerLoopWithCheckpoint() throws Exception {
        final String shardId = "shardId-000000000000";
        final String concurrencyToken = "concurrencyToken";
        final ExtendedSequenceNumber firstSequenceNumber = ExtendedSequenceNumber.TRIM_HORIZON;
        final ExtendedSequenceNumber secondSequenceNumber = new ExtendedSequenceNumber("1000");
        final ExtendedSequenceNumber finalSequenceNumber = new ExtendedSequenceNumber("2000");

        final List<ShardInfo> initialShardInfo = Collections.singletonList(
                new ShardInfo(shardId, concurrencyToken, null, firstSequenceNumber));
        final List<ShardInfo> firstShardInfo = Collections.singletonList(
                new ShardInfo(shardId, concurrencyToken, null, secondSequenceNumber));
        final List<ShardInfo> secondShardInfo = Collections.singletonList(
                new ShardInfo(shardId, concurrencyToken, null, finalSequenceNumber));

        final Checkpoint firstCheckpoint = new Checkpoint(firstSequenceNumber, null, null);

        when(leaseCoordinator.getCurrentAssignments()).thenReturn(initialShardInfo, firstShardInfo, secondShardInfo);
        when(checkpoint.getCheckpointObject(eq(shardId))).thenReturn(firstCheckpoint);

        Scheduler schedulerSpy = spy(scheduler);
        schedulerSpy.runProcessLoop();
        schedulerSpy.runProcessLoop();
        schedulerSpy.runProcessLoop();

        verify(schedulerSpy).buildConsumer(same(initialShardInfo.get(0)), eq(shardRecordProcessorFactory));
        verify(schedulerSpy, never()).buildConsumer(same(firstShardInfo.get(0)), eq(shardRecordProcessorFactory));
        verify(schedulerSpy, never()).buildConsumer(same(secondShardInfo.get(0)), eq(shardRecordProcessorFactory));
        verify(checkpoint).getCheckpointObject(eq(shardId));
    }

    @Test
    public final void testCleanupShardConsumers() {
        final String shard0 = "shardId-000000000000";
        final String shard1 = "shardId-000000000001";
        final String concurrencyToken = "concurrencyToken";
        final String anotherConcurrencyToken = "anotherConcurrencyToken";

        final ShardInfo shardInfo0 = new ShardInfo(shard0, concurrencyToken, null, ExtendedSequenceNumber.TRIM_HORIZON);
        final ShardInfo shardInfo0WithAnotherConcurrencyToken = new ShardInfo(shard0, anotherConcurrencyToken, null,
                ExtendedSequenceNumber.TRIM_HORIZON);
        final ShardInfo shardInfo1 = new ShardInfo(shard1, concurrencyToken, null, ExtendedSequenceNumber.TRIM_HORIZON);

        final ShardConsumer shardConsumer0 = scheduler.createOrGetShardConsumer(shardInfo0, shardRecordProcessorFactory);
        final ShardConsumer shardConsumer0WithAnotherConcurrencyToken =
                scheduler.createOrGetShardConsumer(shardInfo0WithAnotherConcurrencyToken, shardRecordProcessorFactory);
        final ShardConsumer shardConsumer1 = scheduler.createOrGetShardConsumer(shardInfo1, shardRecordProcessorFactory);

        Set<ShardInfo> shards = new HashSet<>();
        shards.add(shardInfo0);
        shards.add(shardInfo1);
        scheduler.cleanupShardConsumers(shards);

        // verify shard consumer not present in assignedShards is shut down
        assertTrue(shardConsumer0WithAnotherConcurrencyToken.isShutdownRequested());
        // verify shard consumers present in assignedShards aren't shut down
        assertFalse(shardConsumer0.isShutdownRequested());
        assertFalse(shardConsumer1.isShutdownRequested());
    }

    @Test
    public final void testInitializationFailureWithRetries() throws Exception {
        doNothing().when(leaseCoordinator).initialize();
        when(shardDetector.listShards()).thenThrow(new RuntimeException());
        leaseManagementConfig = new LeaseManagementConfig(tableName, dynamoDBClient, kinesisClient, streamName,
                workerIdentifier).leaseManagementFactory(new TestKinesisLeaseManagementFactory(false, true));
        scheduler = new Scheduler(checkpointConfig, coordinatorConfig, leaseManagementConfig, lifecycleConfig,
                metricsConfig, processorConfig, retrievalConfig);
        scheduler.run();

        verify(shardDetector, times(coordinatorConfig.maxInitializationAttempts())).listShards();
    }

    @Test
    public final void testInitializationFailureWithRetriesWithConfiguredMaxInitializationAttempts() throws Exception {
        final int maxInitializationAttempts = 5;
        coordinatorConfig.maxInitializationAttempts(maxInitializationAttempts);
        leaseManagementConfig = new LeaseManagementConfig(tableName, dynamoDBClient, kinesisClient, streamName,
                workerIdentifier).leaseManagementFactory(new TestKinesisLeaseManagementFactory(false, true));
        scheduler = new Scheduler(checkpointConfig, coordinatorConfig, leaseManagementConfig, lifecycleConfig,
                metricsConfig, processorConfig, retrievalConfig);

        doNothing().when(leaseCoordinator).initialize();
        when(shardDetector.listShards()).thenThrow(new RuntimeException());

        scheduler.run();

        // verify initialization was retried for maxInitializationAttempts times
        verify(shardDetector, times(maxInitializationAttempts)).listShards();
    }

    @Test
    public final void testMultiStreamInitialization() throws ProvisionedThroughputException, DependencyException {
        retrievalConfig = new RetrievalConfig(kinesisClient, multiStreamTracker, applicationName)
                .retrievalFactory(retrievalFactory);
        scheduler = new Scheduler(checkpointConfig, coordinatorConfig, leaseManagementConfig, lifecycleConfig,
                metricsConfig, processorConfig, retrievalConfig);
        scheduler.initialize();
        shardDetectorMap.values().stream()
                .forEach(shardDetector -> verify(shardDetector, times(1)).listShards());
    }

    @Test
    public final void testMultiStreamInitializationWithFailures() {
        retrievalConfig = new RetrievalConfig(kinesisClient, multiStreamTracker, applicationName)
                .retrievalFactory(retrievalFactory);
        leaseManagementConfig = new LeaseManagementConfig(tableName, dynamoDBClient, kinesisClient,
                workerIdentifier).leaseManagementFactory(new TestKinesisLeaseManagementFactory(true, false));
        scheduler = new Scheduler(checkpointConfig, coordinatorConfig, leaseManagementConfig, lifecycleConfig,
                metricsConfig, processorConfig, retrievalConfig);
        scheduler.initialize();
        // Note : As of today we retry for all streams in the next attempt. Hence the retry for each stream will vary.
        //        At the least we expect 2 retries for each stream. Since there are 4 streams, we expect at most
        //        the number of calls to be 5.
        shardDetectorMap.values().stream()
                .forEach(shardDetector -> verify(shardDetector, atLeast(2)).listShards());
        shardDetectorMap.values().stream()
                .forEach(shardDetector -> verify(shardDetector, atMost(5)).listShards());
    }


    @Test
    public final void testMultiStreamConsumersAreBuiltOncePerAccountStreamShard() throws KinesisClientLibException {
        final String shardId = "shardId-000000000000";
        final String concurrencyToken = "concurrencyToken";
        final ExtendedSequenceNumber firstSequenceNumber = ExtendedSequenceNumber.TRIM_HORIZON;
        final ExtendedSequenceNumber secondSequenceNumber = new ExtendedSequenceNumber("1000");
        final ExtendedSequenceNumber finalSequenceNumber = new ExtendedSequenceNumber("2000");

        final List<ShardInfo> initialShardInfo = multiStreamTracker.streamConfigList().stream()
                .map(sc -> new ShardInfo(shardId, concurrencyToken, null, firstSequenceNumber,
                        sc.streamIdentifier().serialize())).collect(Collectors.toList());
        final List<ShardInfo> firstShardInfo = multiStreamTracker.streamConfigList().stream()
                .map(sc -> new ShardInfo(shardId, concurrencyToken, null, secondSequenceNumber,
                        sc.streamIdentifier().serialize())).collect(Collectors.toList());
        final List<ShardInfo> secondShardInfo = multiStreamTracker.streamConfigList().stream()
                .map(sc -> new ShardInfo(shardId, concurrencyToken, null, finalSequenceNumber,
                        sc.streamIdentifier().serialize())).collect(Collectors.toList());

        final Checkpoint firstCheckpoint = new Checkpoint(firstSequenceNumber, null, null);

        when(leaseCoordinator.getCurrentAssignments()).thenReturn(initialShardInfo, firstShardInfo, secondShardInfo);
        when(checkpoint.getCheckpointObject(anyString())).thenReturn(firstCheckpoint);
        retrievalConfig = new RetrievalConfig(kinesisClient, multiStreamTracker, applicationName)
                .retrievalFactory(retrievalFactory);
        scheduler = new Scheduler(checkpointConfig, coordinatorConfig, leaseManagementConfig, lifecycleConfig,
                metricsConfig, processorConfig, retrievalConfig);
        Scheduler schedulerSpy = spy(scheduler);
        schedulerSpy.runProcessLoop();
        schedulerSpy.runProcessLoop();
        schedulerSpy.runProcessLoop();

        initialShardInfo.stream().forEach(
                shardInfo -> verify(schedulerSpy).buildConsumer(same(shardInfo), eq(shardRecordProcessorFactory)));
        firstShardInfo.stream().forEach(
                shardInfo -> verify(schedulerSpy, never()).buildConsumer(same(shardInfo), eq(shardRecordProcessorFactory)));
        secondShardInfo.stream().forEach(
                shardInfo -> verify(schedulerSpy, never()).buildConsumer(same(shardInfo), eq(shardRecordProcessorFactory)));

    }

    @Test
    public final void testMultiStreamNoStreamsAreSyncedWhenStreamsAreNotRefreshed()
            throws DependencyException, ProvisionedThroughputException, InvalidStateException {
        List<StreamConfig> streamConfigList1 = IntStream.range(1, 5).mapToObj(streamId -> new StreamConfig(
                StreamIdentifier.multiStreamInstance(
                        Joiner.on(":").join(streamId * 111111111, "multiStreamTest-" + streamId, streamId * 12345)),
                InitialPositionInStreamExtended.newInitialPosition(InitialPositionInStream.LATEST)))
                .collect(Collectors.toCollection(LinkedList::new));
        List<StreamConfig> streamConfigList2 = IntStream.range(1, 5).mapToObj(streamId -> new StreamConfig(
                StreamIdentifier.multiStreamInstance(
                        Joiner.on(":").join(streamId * 111111111, "multiStreamTest-" + streamId, streamId * 12345)),
                InitialPositionInStreamExtended.newInitialPosition(InitialPositionInStream.LATEST)))
                .collect(Collectors.toCollection(LinkedList::new));
        retrievalConfig = new RetrievalConfig(kinesisClient, multiStreamTracker, applicationName)
                .retrievalFactory(retrievalFactory);
        when(multiStreamTracker.streamConfigList()).thenReturn(streamConfigList1, streamConfigList2);
        scheduler = spy(new Scheduler(checkpointConfig, coordinatorConfig, leaseManagementConfig, lifecycleConfig,
                metricsConfig, processorConfig, retrievalConfig));
        when(scheduler.shouldSyncStreamsNow()).thenReturn(true);
        Set<StreamIdentifier> syncedStreams = scheduler.checkAndSyncStreamShardsAndLeases();
        Assert.assertTrue("SyncedStreams should be empty", syncedStreams.isEmpty());
        Assert.assertEquals(new HashSet(streamConfigList1), new HashSet(scheduler.currentStreamConfigMap().values()));
    }

    @Test
    public final void testMultiStreamOnlyNewStreamsAreSynced()
            throws DependencyException, ProvisionedThroughputException, InvalidStateException {
        List<StreamConfig> streamConfigList1 = IntStream.range(1, 5).mapToObj(streamId -> new StreamConfig(
                StreamIdentifier.multiStreamInstance(
                        Joiner.on(":").join(streamId * 111111111, "multiStreamTest-" + streamId, streamId * 12345)),
                InitialPositionInStreamExtended.newInitialPosition(InitialPositionInStream.LATEST)))
                .collect(Collectors.toCollection(LinkedList::new));
        List<StreamConfig> streamConfigList2 = IntStream.range(1, 7).mapToObj(streamId -> new StreamConfig(
                StreamIdentifier.multiStreamInstance(
                        Joiner.on(":").join(streamId * 111111111, "multiStreamTest-" + streamId, streamId * 12345)),
                InitialPositionInStreamExtended.newInitialPosition(InitialPositionInStream.LATEST)))
                .collect(Collectors.toCollection(LinkedList::new));
        retrievalConfig = new RetrievalConfig(kinesisClient, multiStreamTracker, applicationName)
                .retrievalFactory(retrievalFactory);
        when(multiStreamTracker.streamConfigList()).thenReturn(streamConfigList1, streamConfigList2);
        scheduler = spy(new Scheduler(checkpointConfig, coordinatorConfig, leaseManagementConfig, lifecycleConfig,
                metricsConfig, processorConfig, retrievalConfig));
        when(scheduler.shouldSyncStreamsNow()).thenReturn(true);
        Set<StreamIdentifier> syncedStreams = scheduler.checkAndSyncStreamShardsAndLeases();
        Set<StreamIdentifier> expectedSyncedStreams = IntStream.range(5, 7).mapToObj(streamId -> StreamIdentifier.multiStreamInstance(
                Joiner.on(":").join(streamId * 111111111, "multiStreamTest-" + streamId, streamId * 12345))).collect(
                Collectors.toCollection(HashSet::new));
        Assert.assertEquals(expectedSyncedStreams, syncedStreams);
        Assert.assertEquals(Sets.newHashSet(streamConfigList2),
                Sets.newHashSet(scheduler.currentStreamConfigMap().values()));
    }

    @Test
    public final void testMultiStreamStaleStreamsAreNotDeletedImmediately()
            throws DependencyException, ProvisionedThroughputException, InvalidStateException {
        List<StreamConfig> streamConfigList1 = IntStream.range(1, 5).mapToObj(streamId -> new StreamConfig(
                StreamIdentifier.multiStreamInstance(
                        Joiner.on(":").join(streamId * 111111111, "multiStreamTest-" + streamId, streamId * 12345)),
                InitialPositionInStreamExtended.newInitialPosition(InitialPositionInStream.LATEST)))
                .collect(Collectors.toCollection(LinkedList::new));
        List<StreamConfig> streamConfigList2 = IntStream.range(3, 5).mapToObj(streamId -> new StreamConfig(
                StreamIdentifier.multiStreamInstance(
                        Joiner.on(":").join(streamId * 111111111, "multiStreamTest-" + streamId, streamId * 12345)),
                InitialPositionInStreamExtended.newInitialPosition(InitialPositionInStream.LATEST)))
                .collect(Collectors.toCollection(LinkedList::new));
        retrievalConfig = new RetrievalConfig(kinesisClient, multiStreamTracker, applicationName)
                .retrievalFactory(retrievalFactory);
        when(multiStreamTracker.streamConfigList()).thenReturn(streamConfigList1, streamConfigList2);
        scheduler = spy(new Scheduler(checkpointConfig, coordinatorConfig, leaseManagementConfig, lifecycleConfig,
                metricsConfig, processorConfig, retrievalConfig));
        when(scheduler.shouldSyncStreamsNow()).thenReturn(true);
        Set<StreamIdentifier> syncedStreams = scheduler.checkAndSyncStreamShardsAndLeases();
        Set<StreamIdentifier> expectedPendingStreams = IntStream.range(1, 3).mapToObj(streamId -> StreamIdentifier.multiStreamInstance(
                Joiner.on(":").join(streamId * 111111111, "multiStreamTest-" + streamId, streamId * 12345))).collect(
                Collectors.toCollection(HashSet::new));
        Assert.assertEquals(Sets.newHashSet(), syncedStreams);
        Assert.assertEquals(Sets.newHashSet(streamConfigList1),
                Sets.newHashSet(scheduler.currentStreamConfigMap().values()));
        Assert.assertEquals(expectedPendingStreams,
                scheduler.staleStreamDeletionMap().keySet());
    }

    @Test
    public final void testMultiStreamStaleStreamsAreDeletedAfterDefermentPeriod()
            throws DependencyException, ProvisionedThroughputException, InvalidStateException {
        List<StreamConfig> streamConfigList1 = IntStream.range(1, 5).mapToObj(streamId -> new StreamConfig(
                StreamIdentifier.multiStreamInstance(
                        Joiner.on(":").join(streamId * 111111111, "multiStreamTest-" + streamId, streamId * 12345)),
                InitialPositionInStreamExtended.newInitialPosition(InitialPositionInStream.LATEST)))
                .collect(Collectors.toCollection(LinkedList::new));
        List<StreamConfig> streamConfigList2 = IntStream.range(3, 5).mapToObj(streamId -> new StreamConfig(
                StreamIdentifier.multiStreamInstance(
                        Joiner.on(":").join(streamId * 111111111, "multiStreamTest-" + streamId, streamId * 12345)),
                InitialPositionInStreamExtended.newInitialPosition(InitialPositionInStream.LATEST)))
                .collect(Collectors.toCollection(LinkedList::new));
        retrievalConfig = new RetrievalConfig(kinesisClient, multiStreamTracker, applicationName)
                .retrievalFactory(retrievalFactory);
        when(multiStreamTracker.streamConfigList()).thenReturn(streamConfigList1, streamConfigList2);
        scheduler = spy(new Scheduler(checkpointConfig, coordinatorConfig, leaseManagementConfig, lifecycleConfig,
                metricsConfig, processorConfig, retrievalConfig));
        when(scheduler.shouldSyncStreamsNow()).thenReturn(true);
        when(scheduler.getOldStreamDeferredDeletionPeriodMillis()).thenReturn(0L);
        Set<StreamIdentifier> syncedStreams = scheduler.checkAndSyncStreamShardsAndLeases();
        Set<StreamIdentifier> expectedSyncedStreams = IntStream.range(1, 3).mapToObj(streamId -> StreamIdentifier.multiStreamInstance(
                Joiner.on(":").join(streamId * 111111111, "multiStreamTest-" + streamId, streamId * 12345))).collect(
                Collectors.toCollection(HashSet::new));
        Assert.assertEquals(expectedSyncedStreams, syncedStreams);
        Assert.assertEquals(Sets.newHashSet(streamConfigList2),
                Sets.newHashSet(scheduler.currentStreamConfigMap().values()));
        Assert.assertEquals(Sets.newHashSet(),
                scheduler.staleStreamDeletionMap().keySet());
    }

    @Test
    public final void testMultiStreamNewStreamsAreSyncedAndStaleStreamsAreNotDeletedImmediately()
            throws DependencyException, ProvisionedThroughputException, InvalidStateException {
        List<StreamConfig> streamConfigList1 = IntStream.range(1, 5).mapToObj(streamId -> new StreamConfig(
                StreamIdentifier.multiStreamInstance(
                        Joiner.on(":").join(streamId * 111111111, "multiStreamTest-" + streamId, streamId * 12345)),
                InitialPositionInStreamExtended.newInitialPosition(InitialPositionInStream.LATEST)))
                .collect(Collectors.toCollection(LinkedList::new));
        List<StreamConfig> streamConfigList2 = IntStream.range(3, 7).mapToObj(streamId -> new StreamConfig(
                StreamIdentifier.multiStreamInstance(
                        Joiner.on(":").join(streamId * 111111111, "multiStreamTest-" + streamId, streamId * 12345)),
                InitialPositionInStreamExtended.newInitialPosition(InitialPositionInStream.LATEST)))
                .collect(Collectors.toCollection(LinkedList::new));
        retrievalConfig = new RetrievalConfig(kinesisClient, multiStreamTracker, applicationName)
                .retrievalFactory(retrievalFactory);
        when(multiStreamTracker.streamConfigList()).thenReturn(streamConfigList1, streamConfigList2);
        scheduler = spy(new Scheduler(checkpointConfig, coordinatorConfig, leaseManagementConfig, lifecycleConfig,
                metricsConfig, processorConfig, retrievalConfig));
        when(scheduler.shouldSyncStreamsNow()).thenReturn(true);
        Set<StreamIdentifier> syncedStreams = scheduler.checkAndSyncStreamShardsAndLeases();
        Set<StreamIdentifier> expectedSyncedStreams = IntStream.range(5, 7)
                .mapToObj(streamId -> StreamIdentifier.multiStreamInstance(
                        Joiner.on(":").join(streamId * 111111111, "multiStreamTest-" + streamId, streamId * 12345)))
                .collect(Collectors.toCollection(HashSet::new));
        Set<StreamIdentifier> expectedPendingStreams = IntStream.range(1, 3)
                .mapToObj(streamId -> StreamIdentifier.multiStreamInstance(
                        Joiner.on(":").join(streamId * 111111111, "multiStreamTest-" + streamId, streamId * 12345)))
                .collect(Collectors.toCollection(HashSet::new));
        Assert.assertEquals(expectedSyncedStreams, syncedStreams);
        List<StreamConfig> expectedCurrentStreamConfigs = IntStream.range(1, 7).mapToObj(streamId -> new StreamConfig(
                StreamIdentifier.multiStreamInstance(
                        Joiner.on(":").join(streamId * 111111111, "multiStreamTest-" + streamId, streamId * 12345)),
                InitialPositionInStreamExtended.newInitialPosition(InitialPositionInStream.LATEST)))
                .collect(Collectors.toCollection(LinkedList::new));
        Assert.assertEquals(Sets.newHashSet(expectedCurrentStreamConfigs),
                Sets.newHashSet(scheduler.currentStreamConfigMap().values()));
        Assert.assertEquals(expectedPendingStreams,
                scheduler.staleStreamDeletionMap().keySet());
    }

    @Test
    public final void testMultiStreamNewStreamsAreSyncedAndStaleStreamsAreDeletedAfterDefermentPeriod()
            throws DependencyException, ProvisionedThroughputException, InvalidStateException {
        List<StreamConfig> streamConfigList1 = IntStream.range(1, 5).mapToObj(streamId -> new StreamConfig(
                StreamIdentifier.multiStreamInstance(
                        Joiner.on(":").join(streamId * 111111111, "multiStreamTest-" + streamId, streamId * 12345)),
                InitialPositionInStreamExtended.newInitialPosition(InitialPositionInStream.LATEST)))
                .collect(Collectors.toCollection(LinkedList::new));
        List<StreamConfig> streamConfigList2 = IntStream.range(3, 7).mapToObj(streamId -> new StreamConfig(
                StreamIdentifier.multiStreamInstance(
                        Joiner.on(":").join(streamId * 111111111, "multiStreamTest-" + streamId, streamId * 12345)),
                InitialPositionInStreamExtended.newInitialPosition(InitialPositionInStream.LATEST)))
                .collect(Collectors.toCollection(LinkedList::new));
        retrievalConfig = new RetrievalConfig(kinesisClient, multiStreamTracker, applicationName)
                .retrievalFactory(retrievalFactory);
        when(multiStreamTracker.streamConfigList()).thenReturn(streamConfigList1, streamConfigList2);
        scheduler = spy(new Scheduler(checkpointConfig, coordinatorConfig, leaseManagementConfig, lifecycleConfig,
                metricsConfig, processorConfig, retrievalConfig));
        when(scheduler.shouldSyncStreamsNow()).thenReturn(true);
        when(scheduler.getOldStreamDeferredDeletionPeriodMillis()).thenReturn(0L);
        Set<StreamIdentifier> syncedStreams = scheduler.checkAndSyncStreamShardsAndLeases();
        Set<StreamIdentifier> expectedSyncedStreams = IntStream.concat(IntStream.range(1, 3), IntStream.range(5, 7))
                .mapToObj(streamId -> StreamIdentifier.multiStreamInstance(
                        Joiner.on(":").join(streamId * 111111111, "multiStreamTest-" + streamId, streamId * 12345)))
                .collect(Collectors.toCollection(HashSet::new));
        Assert.assertEquals(expectedSyncedStreams, syncedStreams);
        Assert.assertEquals(Sets.newHashSet(streamConfigList2),
                Sets.newHashSet(scheduler.currentStreamConfigMap().values()));
        Assert.assertEquals(Sets.newHashSet(),
                scheduler.staleStreamDeletionMap().keySet());
    }

    @Test
    public final void testSchedulerShutdown() {
        scheduler.shutdown();
        verify(workerStateChangeListener, times(1)).onWorkerStateChange(WorkerStateChangeListener.WorkerState.SHUT_DOWN_STARTED);
        verify(leaseCoordinator, times(1)).stop();
        verify(workerStateChangeListener, times(1)).onWorkerStateChange(WorkerStateChangeListener.WorkerState.SHUT_DOWN);
    }

    @Test
    public void testErrorHandlerForUndeliverableAsyncTaskExceptions() {
        DiagnosticEventFactory eventFactory = mock(DiagnosticEventFactory.class);
        ExecutorStateEvent executorStateEvent = mock(ExecutorStateEvent.class);
        RejectedTaskEvent rejectedTaskEvent = mock(RejectedTaskEvent.class);

        when(eventFactory.rejectedTaskEvent(any(), any())).thenReturn(rejectedTaskEvent);
        when(eventFactory.executorStateEvent(any(), any())).thenReturn(executorStateEvent);

        Scheduler testScheduler = new Scheduler(checkpointConfig, coordinatorConfig, leaseManagementConfig,
                lifecycleConfig, metricsConfig, processorConfig, retrievalConfig, eventFactory);

        Scheduler schedulerSpy = spy(testScheduler);

        // reject task on third loop
        doCallRealMethod()
                .doCallRealMethod()
                .doAnswer(invocation -> {
                    // trigger rejected task in RxJava layer
                     RxJavaPlugins.onError(new RejectedExecutionException("Test exception."));
                     return null;
                }).when(schedulerSpy).runProcessLoop();

        // Scheduler sets error handler in initialize method
        schedulerSpy.initialize();
        schedulerSpy.runProcessLoop();
        schedulerSpy.runProcessLoop();
        schedulerSpy.runProcessLoop();

        verify(eventFactory, times(1)).rejectedTaskEvent(eq(executorStateEvent), any());
        verify(rejectedTaskEvent, times(1)).accept(any());
    }

    /*private void runAndTestWorker(int numShards, int threadPoolSize) throws Exception {
        final int numberOfRecordsPerShard = 10;
        final String kinesisShardPrefix = "kinesis-0-";
        final BigInteger startSeqNum = BigInteger.ONE;
        List<Shard> shardList = KinesisLocalFileDataCreator.createShardList(numShards, kinesisShardPrefix, startSeqNum);
        Assert.assertEquals(numShards, shardList.size());
        List<Lease> initialLeases = new ArrayList<Lease>();
        for (Shard shard : shardList) {
            Lease lease = ShardSyncer.newKCLLease(shard);
            lease.setCheckpoint(ExtendedSequenceNumber.AT_TIMESTAMP);
            initialLeases.add(lease);
        }
        runAndTestWorker(shardList, threadPoolSize, initialLeases, numberOfRecordsPerShard);
    }

    private void runAndTestWorker(List<Shard> shardList,
                                  int threadPoolSize,
                                  List<Lease> initialLeases,
                                  int numberOfRecordsPerShard) throws Exception {
        File file = KinesisLocalFileDataCreator.generateTempDataFile(shardList, numberOfRecordsPerShard, "unitTestWT001");
        IKinesisProxy fileBasedProxy = new KinesisLocalFileProxy(file.getAbsolutePath());

        Semaphore recordCounter = new Semaphore(0);
        ShardSequenceVerifier shardSequenceVerifier = new ShardSequenceVerifier(shardList);
        TestStreamletFactory recordProcessorFactory = new TestStreamletFactory(recordCounter, shardSequenceVerifier);

        ExecutorService executorService = Executors.newFixedThreadPool(threadPoolSize);

        SchedulerThread schedulerThread = runWorker(initialLeases);

        // TestStreamlet will release the semaphore once for every record it processes
        recordCounter.acquire(numberOfRecordsPerShard * shardList.size());

        // Wait a bit to allow the worker to spin against the end of the stream.
        Thread.sleep(500L);

        testWorker(shardList, threadPoolSize, initialLeases,
                numberOfRecordsPerShard, fileBasedProxy, recordProcessorFactory);

        schedulerThread.schedulerForThread().shutdown();
        executorService.shutdownNow();
        file.delete();
    }

    private SchedulerThread runWorker(final List<Lease> initialLeases) throws Exception {
        final int maxRecords = 2;

        final long leaseDurationMillis = 10000L;
        final long epsilonMillis = 1000L;
        final long idleTimeInMilliseconds = 2L;

        AmazonDynamoDB ddbClient = DynamoDBEmbedded.create().dynamoDBClient();
        LeaseManager<Lease> leaseRefresher = new LeaseManager("foo", ddbClient);
        leaseRefresher.createLeaseTableIfNotExists(1L, 1L);
        for (Lease initialLease : initialLeases) {
            leaseRefresher.createLeaseIfNotExists(initialLease);
        }

        checkpointConfig = new CheckpointConfig("foo", ddbClient, workerIdentifier)
                .failoverTimeMillis(leaseDurationMillis)
                .epsilonMillis(epsilonMillis)
                .leaseRefresher(leaseRefresher);
        leaseManagementConfig = new LeaseManagementConfig("foo", ddbClient, kinesisClient, streamName, workerIdentifier)
                .failoverTimeMillis(leaseDurationMillis)
                .epsilonMillis(epsilonMillis);
        retrievalConfig.initialPositionInStreamExtended(InitialPositionInStreamExtended.newInitialPositionAtTimestamp(
                        new Date(KinesisLocalFileDataCreator.STARTING_TIMESTAMP)))
                .maxRecords(maxRecords)
                .idleTimeBetweenReadsInMillis(idleTimeInMilliseconds);
        scheduler = new Scheduler(checkpointConfig, coordinatorConfig, leaseManagementConfig, lifecycleConfig,
                metricsConfig, processorConfig, retrievalConfig);

        SchedulerThread schedulerThread = new SchedulerThread(scheduler);
        schedulerThread.start();
        return schedulerThread;
    }

    private void testWorker(List<Shard> shardList,
                            int threadPoolSize,
                            List<Lease> initialLeases,
                            int numberOfRecordsPerShard,
                            IKinesisProxy kinesisProxy,
                            TestStreamletFactory recordProcessorFactory) throws Exception {
        recordProcessorFactory.getShardSequenceVerifier().verify();

        // Gather values to compare across all processors of a given shard.
        Map<String, List<Record>> shardStreamletsRecords = new HashMap<String, List<Record>>();
        Map<String, ShutdownReason> shardsLastProcessorShutdownReason = new HashMap<String, ShutdownReason>();
        Map<String, Long> shardsNumProcessRecordsCallsWithEmptyRecordList = new HashMap<String, Long>();
        for (TestStreamlet processor : recordProcessorFactory.getTestStreamlets()) {
            String shardId = processor.shardId();
            if (shardStreamletsRecords.get(shardId) == null) {
                shardStreamletsRecords.put(shardId, processor.getProcessedRecords());
            } else {
                List<Record> records = shardStreamletsRecords.get(shardId);
                records.addAll(processor.getProcessedRecords());
                shardStreamletsRecords.put(shardId, records);
            }
            if (shardsNumProcessRecordsCallsWithEmptyRecordList.get(shardId) == null) {
                shardsNumProcessRecordsCallsWithEmptyRecordList.put(shardId,
                        processor.getNumProcessRecordsCallsWithEmptyRecordList());
            } else {
                long totalShardsNumProcessRecordsCallsWithEmptyRecordList =
                        shardsNumProcessRecordsCallsWithEmptyRecordList.get(shardId)
                                + processor.getNumProcessRecordsCallsWithEmptyRecordList();
                shardsNumProcessRecordsCallsWithEmptyRecordList.put(shardId,
                        totalShardsNumProcessRecordsCallsWithEmptyRecordList);
            }
            shardsLastProcessorShutdownReason.put(processor.shardId(), processor.getShutdownReason());
        }

        // verify that all records were processed at least once
        verifyAllRecordsOfEachShardWereConsumedAtLeastOnce(shardList, kinesisProxy, numberOfRecordsPerShard, shardStreamletsRecords);
        shardList.forEach(shard -> {
            final String iterator = kinesisProxy.getIterator(shard.shardId(), new Date(KinesisLocalFileDataCreator.STARTING_TIMESTAMP));
            final List<Record> records = kinesisProxy.get(iterator, numberOfRecordsPerShard).records();
            assertEquals();
        });
        for (Shard shard : shardList) {
            String shardId = shard.shardId();
            String iterator =
                    fileBasedProxy.getIterator(shardId, new Date(KinesisLocalFileDataCreator.STARTING_TIMESTAMP));
            List<Record> expectedRecords = fileBasedProxy.get(iterator, numRecs).records();
            verifyAllRecordsWereConsumedAtLeastOnce(expectedRecords, shardStreamletsRecords.get(shardId));
        }

        // within a record processor all the incoming records should be ordered
        verifyRecordsProcessedByEachProcessorWereOrdered(recordProcessorFactory);

        // for shards for which only one record processor was created, we verify that each record should be
        // processed exactly once
        verifyAllRecordsOfEachShardWithOnlyOneProcessorWereConsumedExactlyOnce(shardList,
                kinesisProxy,
                numberOfRecordsPerShard,
                shardStreamletsRecords,
                recordProcessorFactory);

        // if callProcessRecordsForEmptyRecordList flag is set then processors must have been invoked with empty record
        // sets else they shouldn't have seen invoked with empty record sets
        verifyNumProcessRecordsCallsWithEmptyRecordList(shardList,
                shardsNumProcessRecordsCallsWithEmptyRecordList,
                callProcessRecordsForEmptyRecordList);

        // verify that worker shutdown last processor of shards that were terminated
        verifyLastProcessorOfClosedShardsWasShutdownWithTerminate(shardList, shardsLastProcessorShutdownReason);
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    @Accessors(fluent = true)
    private static class SchedulerThread extends Thread {
        private final Scheduler schedulerForThread;
    }*/

    private static class TestShardRecordProcessorFactory implements ShardRecordProcessorFactory {
        @Override
        public ShardRecordProcessor shardRecordProcessor() {
            return new ShardRecordProcessor() {
                @Override
                public void initialize(final InitializationInput initializationInput) {
                    // Do nothing.
                }

                @Override
                public void processRecords(final ProcessRecordsInput processRecordsInput) {
                    try {
                        processRecordsInput.checkpointer().checkpoint();
                    } catch (KinesisClientLibNonRetryableException e) {
                        throw new RuntimeException(e);
                    }
                }

                @Override
                public void leaseLost(LeaseLostInput leaseLostInput) {

                }

                @Override
                public void shardEnded(ShardEndedInput shardEndedInput) {
                    try {
                        shardEndedInput.checkpointer().checkpoint();
                    } catch (KinesisClientLibNonRetryableException e) {
                        throw new RuntimeException(e);
                    }
                }

                @Override
                public void shutdownRequested(ShutdownRequestedInput shutdownRequestedInput) {

                }
            };
        }

        @Override
        public ShardRecordProcessor shardRecordProcessor(StreamIdentifier streamIdentifier) {
            return shardRecordProcessor();
        }

    }

    @RequiredArgsConstructor
    private class TestKinesisLeaseManagementFactory implements LeaseManagementFactory {

        private final boolean shardSyncFirstAttemptFailure;
        private final boolean shouldReturnDefaultShardSyncTaskmanager;

        @Override
        public LeaseCoordinator createLeaseCoordinator(MetricsFactory metricsFactory) {
            return leaseCoordinator;
        }

        @Override
        public ShardSyncTaskManager createShardSyncTaskManager(MetricsFactory metricsFactory) {
            return shardSyncTaskManager;
        }

        @Override
        public ShardSyncTaskManager createShardSyncTaskManager(MetricsFactory metricsFactory,
                StreamConfig streamConfig) {
            if(shouldReturnDefaultShardSyncTaskmanager) {
                return shardSyncTaskManager;
            }
            final ShardSyncTaskManager shardSyncTaskManager = mock(ShardSyncTaskManager.class);
            final ShardDetector shardDetector = mock(ShardDetector.class);
            shardSyncTaskManagerMap.put(streamConfig.streamIdentifier(), shardSyncTaskManager);
            shardDetectorMap.put(streamConfig.streamIdentifier(), shardDetector);
            when(shardSyncTaskManager.shardDetector()).thenReturn(shardDetector);
            when(shardDetector.streamIdentifier()).thenReturn(streamConfig.streamIdentifier());
            if(shardSyncFirstAttemptFailure) {
                when(shardDetector.listShards())
                        .thenThrow(new RuntimeException("Service Exception"))
                        .thenReturn(Collections.EMPTY_LIST);
            }
            return shardSyncTaskManager;
        }

        @Override
        public DynamoDBLeaseRefresher createLeaseRefresher() {
            return dynamoDBLeaseRefresher;
        }

        @Override
        public ShardDetector createShardDetector() {
            return shardDetector;
        }

        @Override
        public ShardDetector createShardDetector(StreamConfig streamConfig) {
            return shardDetectorMap.get(streamConfig.streamIdentifier());
        }
    }

    private class TestKinesisCheckpointFactory implements CheckpointFactory {
        @Override
        public Checkpointer createCheckpointer(final LeaseCoordinator leaseCoordinator,
                                               final LeaseRefresher leaseRefresher) {
            return checkpoint;
        }
    }

}
