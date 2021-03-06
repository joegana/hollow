/*
 *
 *  Copyright 2017 Netflix, Inc.
 *
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 *
 */
package com.netflix.hollow.api.consumer;

import com.netflix.hollow.api.producer.HollowProducer;
import com.netflix.hollow.api.producer.HollowProducer.Populator;
import com.netflix.hollow.api.producer.HollowProducer.ReadState;
import com.netflix.hollow.api.producer.HollowProducer.Validator;
import com.netflix.hollow.api.producer.HollowProducer.Validator.ValidationException;
import com.netflix.hollow.api.producer.HollowProducer.VersionMinter;
import com.netflix.hollow.api.producer.HollowProducer.WriteState;
import com.netflix.hollow.api.producer.fs.HollowInMemoryBlobStager;
import com.netflix.hollow.core.read.engine.object.HollowObjectTypeReadState;
import com.netflix.hollow.tools.compact.HollowCompactor.CompactionConfig;
import java.util.BitSet;
import java.util.concurrent.Executor;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class HollowProducerConsumerTests {
    
    private InMemoryBlobStore blobStore;
    private InMemoryAnnouncement announcement;
    
    @Before
    public void setUp() {
        blobStore = new InMemoryBlobStore();
        announcement = new InMemoryAnnouncement();
    }
    
    @Test
    public void publishAndLoadASnapshot() {
        HollowProducer producer = HollowProducer.withPublisher(blobStore)
                                                .withBlobStager(new HollowInMemoryBlobStager())
                                                .build();
        
        /// Showing verbose version of `runCycle(producer, 1);`
        long version = producer.runCycle(new Populator() {
            public void populate(WriteState state) throws Exception {
                state.add(Integer.valueOf(1));
            }
        });
        
        HollowConsumer consumer = HollowConsumer.withBlobRetriever(blobStore).build();
        consumer.triggerRefreshTo(version);
        
        Assert.assertEquals(version, consumer.getCurrentVersionId());
    }

    @Test
    public void initializationTraversesDeltasToGetUpToDate() {
        HollowProducer producer = HollowProducer.withPublisher(blobStore)
                                                .withBlobStager(new HollowInMemoryBlobStager())
                                                .withNumStatesBetweenSnapshots(2) /// do not produce snapshots for v2 or v3
                                                .build();
        
        long v1 = runCycle(producer, 1);
        long v2 = runCycle(producer, 2);
        long v3 = runCycle(producer, 3);

        HollowConsumer consumer = HollowConsumer.withBlobRetriever(blobStore).build();
        consumer.triggerRefreshTo(v3);
        
        Assert.assertEquals(v3, consumer.getCurrentVersionId());
        
        Assert.assertEquals(v1, blobStore.retrieveSnapshotBlob(v3).getToVersion());
        Assert.assertEquals(v2, blobStore.retrieveDeltaBlob(v1).getToVersion());
        Assert.assertEquals(v3, blobStore.retrieveDeltaBlob(v2).getToVersion());
    }
    
    @Test
    public void consumerAutomaticallyUpdatesBasedOnAnnouncement() {
        HollowProducer producer = HollowProducer.withPublisher(blobStore)
                                                .withAnnouncer(announcement)
                                                .withBlobStager(new HollowInMemoryBlobStager())
                                                .build();
        
        long v1 = runCycle(producer, 1);
        
        HollowConsumer consumer = HollowConsumer.withBlobRetriever(blobStore)
                                                .withAnnouncementWatcher(announcement)
                                                .build();
        consumer.triggerRefresh();
        
        Assert.assertEquals(v1, consumer.getCurrentVersionId());
        
        long v2 = runCycle(producer, 2);
        
        Assert.assertEquals(v2, consumer.getCurrentVersionId());
    }
    
    @Test
    public void consumerFollowsReverseDeltas() {
        HollowProducer producer = HollowProducer.withPublisher(blobStore)
                                                .withBlobStager(new HollowInMemoryBlobStager())
                                                .withNumStatesBetweenSnapshots(2) /// do not produce snapshot for v2 or v3
                                                .build();
        
        long v1 = runCycle(producer, 1);
                  runCycle(producer, 2);
        long v3 = runCycle(producer, 3);
        
        HollowConsumer consumer = HollowConsumer.withBlobRetriever(blobStore).build();
        consumer.triggerRefreshTo(v3);
        
        Assert.assertEquals(v3, consumer.getCurrentVersionId());
        
        blobStore.removeSnapshot(v1); // <-- not necessary to cause following of reverse deltas -- just asserting that's what happened.
        consumer.triggerRefreshTo(v1);

        Assert.assertEquals(v1, consumer.getCurrentVersionId());
    }
        
    @Test
    public void consumerRespondsToPinnedAnnouncement() {
        HollowProducer producer = HollowProducer.withPublisher(blobStore)
                                                .withAnnouncer(announcement)
                                                .withBlobStager(new HollowInMemoryBlobStager())
                                                .withNumStatesBetweenSnapshots(2) /// do not produce snapshot for v2 or v3
                                                .build();

        long v1 = runCycle(producer, 1);
                  runCycle(producer, 2);
        long v3 = runCycle(producer, 3);
        
        
        HollowConsumer consumer = HollowConsumer.withBlobRetriever(blobStore)
                                                .withAnnouncementWatcher(announcement)
                                                .build();
        consumer.triggerRefresh();
        
        Assert.assertEquals(v3, consumer.getCurrentVersionId());
        
        announcement.pin(v1);
        
        Assert.assertEquals(v1, consumer.getCurrentVersionId());
        
        /// another cycle occurs while we're pinned
        long v4 = runCycle(producer, 4);
        
        announcement.unpin();
        
        Assert.assertEquals(v4, consumer.getCurrentVersionId());
    }
    
    @Test
    public void consumerFindsLatestPublishedVersionWithoutAnnouncementWatcher() {
        HollowProducer producer = HollowProducer.withPublisher(blobStore)
                                                .withAnnouncer(announcement)
                                                .withBlobStager(new HollowInMemoryBlobStager())
                                                .build();
        
        long v1 = runCycle(producer, 1);
        
        HollowConsumer consumer = HollowConsumer.withBlobRetriever(blobStore).build();
        
        consumer.triggerRefresh();
        Assert.assertEquals(v1, consumer.getCurrentVersionId());
        
        consumer.triggerRefresh();
        Assert.assertEquals(v1, consumer.getCurrentVersionId());
        
        long v2 = runCycle(producer, 2);
        
        consumer.triggerRefresh();
        Assert.assertEquals(v2, consumer.getCurrentVersionId());
    }

    @Test
    public void producerRestoresAndProducesDelta() {
        HollowProducer producer = HollowProducer.withPublisher(blobStore)
                                                .withBlobStager(new HollowInMemoryBlobStager())
                                                .build();
        
        long v1 = runCycle(producer, 1);
        
        HollowProducer redeployedProducer = HollowProducer.withPublisher(blobStore)
                                                          .withBlobStager(new HollowInMemoryBlobStager())
                                                          .build();
        
        redeployedProducer.initializeDataModel(Integer.class); 
        redeployedProducer.restore(v1, blobStore);
        
        long v2 = runCycle(producer, 2);
        
        Assert.assertNotNull(blobStore.retrieveDeltaBlob(v1));
        Assert.assertEquals(v2, blobStore.retrieveDeltaBlob(v1).getToVersion());
    }
    
    @Test
    public void producerUsesCustomSnapshotPublisherExecutor() {
        HollowProducer producer = HollowProducer.withPublisher(blobStore)
                                                .withBlobStager(new HollowInMemoryBlobStager())
                                                .withSnapshotPublishExecutor(new Executor() {
                                                    public void execute(Runnable command) {
                                                        /// do not publish snapshots!
                                                    }
                                                })
                                                .build();
        
        long v1 = runCycle(producer, 1);
        long v2 = runCycle(producer, 2);
        long v3 = runCycle(producer, 3);
        long v4 = runCycle(producer, 4);
        
        /// first cycle always publishes in-band -- does not use the Executor, so we expect a snapshot for v1.
        Assert.assertEquals(v1, blobStore.retrieveSnapshotBlob(v1).getToVersion());
        Assert.assertEquals(v1, blobStore.retrieveSnapshotBlob(v2).getToVersion());
        Assert.assertEquals(v1, blobStore.retrieveSnapshotBlob(v3).getToVersion());
        Assert.assertEquals(v1, blobStore.retrieveSnapshotBlob(v4).getToVersion());
    }
    
    @Test
    public void producerUsesCustomVersionMinter() {
        HollowProducer producer = HollowProducer.withPublisher(blobStore)
                                                .withBlobStager(new HollowInMemoryBlobStager())
                                                .withVersionMinter(new VersionMinter() {
                                                    long counter = 0;
                                                    public long mint() {
                                                        return ++counter;
                                                    }
                                                })
                                                .build();
        
        long v1 = runCycle(producer, 1);
        long v2 = runCycle(producer, 2);
        long v3 = runCycle(producer, 3);
        
        Assert.assertEquals(1, v1);
        Assert.assertEquals(2, v2);
        Assert.assertEquals(3, v3);
    }
    
    @Test
    public void producerValidates() {
        HollowProducer producer = HollowProducer.withPublisher(blobStore)
                                                .withBlobStager(new HollowInMemoryBlobStager())
                                                .withValidator(new Validator() {
                                                    @Override public void validate(ReadState readState) {
                                                        throw new ValidationException("Expected to fail!");
                                                    }
                                                })
                                                .build();
        
        try {
            runCycle(producer, 1);
            Assert.fail();
        } catch(ValidationException expected) {
            Assert.assertEquals(1, expected.getIndividualFailures().size());
            Assert.assertEquals("Expected to fail!", expected.getIndividualFailures().get(0).getMessage());
        }
    }
    
    @Test
    public void producerCanContinueAfterValidationFailure() {
        HollowProducer producer = HollowProducer.withPublisher(blobStore)
                                                .withBlobStager(new HollowInMemoryBlobStager())
                                                .withValidator(new Validator() {
                                                    int counter=0;
                                                    @Override public void validate(ReadState readState) {
                                                        if(++counter == 2)
                                                            throw new ValidationException("Expected to fail!");
                                                    }
                                                })
                                                .build();
        
        runCycle(producer, 1);
        
        try {
            runCycle(producer, 2);
            Assert.fail();
        } catch(ValidationException expected) {
            Assert.assertEquals(1, expected.getIndividualFailures().size());
            Assert.assertEquals("Expected to fail!", expected.getIndividualFailures().get(0).getMessage());
        }
        
        runCycle(producer, 3);
    }
    
    @Test
    public void producerCompacts() {
        HollowProducer producer = HollowProducer.withPublisher(blobStore)
                                                .withBlobStager(new HollowInMemoryBlobStager())
                                                .build();
        
        producer.runCycle(new Populator() {
            public void populate(WriteState state) throws Exception {
                for(int i=0;i<10000;i++)
                    state.add(Integer.valueOf(i));
            }
        });

        long v2 = producer.runCycle(new Populator() {
            public void populate(WriteState state) throws Exception {
                for(int i=10000;i<20000;i++)
                    state.add(Integer.valueOf(i));
            }
        });
        
        HollowConsumer consumer = HollowConsumer.withBlobRetriever(blobStore).build();
        consumer.triggerRefreshTo(v2);
        
        /// assert that a compaction is now necessary
        long popOrdinalsLength = consumer.getStateEngine().getTypeState("Integer").getPopulatedOrdinals().length();
        Assert.assertEquals(20000, popOrdinalsLength);
        
        /// run a compaction cycle
        long v3 = producer.runCompactionCycle(new CompactionConfig(0, 20));

        /// assert that a compaction actually happened
        consumer.triggerRefreshTo(v3);
        popOrdinalsLength = consumer.getStateEngine().getTypeState("Integer").getPopulatedOrdinals().length();
        Assert.assertEquals(10000, popOrdinalsLength);
        
        BitSet foundValues = new BitSet(20000);
        for(int i=0;i<popOrdinalsLength;i++)
            foundValues.set(((HollowObjectTypeReadState)consumer.getStateEngine().getTypeState("Integer")).readInt(i, 0));
        
        for(int i=10000;i<20000;i++)
            Assert.assertTrue(foundValues.get(i));
    }
    
    private long runCycle(HollowProducer producer, final int cycleNumber) {
        return producer.runCycle(new Populator() {
            public void populate(WriteState state) throws Exception {
                state.add(Integer.valueOf(cycleNumber));
            }
        });
    }
}
