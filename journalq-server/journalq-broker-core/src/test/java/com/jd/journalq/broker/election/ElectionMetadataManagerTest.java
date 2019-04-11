package com.jd.journalq.broker.election;

import com.jd.journalq.domain.PartitionGroup;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

/**
 * Created by zhuduohui on 2018/8/27.
 */
public class ElectionMetadataManagerTest {
    private String metadataFile = "/Users/zhuduohui/jmq/raft_metadata.dat";
    private String metadataPath = "/Users/zhuduohui/jmq/election";
    private ElectionMetadataManager electionMetadataManager;
    private int localNodeId = 1;
    private List<DefaultElectionNode> allNodes = new LinkedList<>();
    private Set<Integer> learners = new HashSet<>();
    private int leaderId = 2;
    private TopicPartitionGroup fixTopic = new TopicPartitionGroup("fix", 1);
    private TopicPartitionGroup raftTopic = new TopicPartitionGroup("raft", 2);
    private ElectionManager electionManager;

    @Before
    public void setUp() throws Exception {
        File file = new File(metadataFile);
        file.delete();

        allNodes.add(new DefaultElectionNode("192.168.0.1:50089", 1));
        allNodes.add(new DefaultElectionNode("192.168.0.2:50089", 2));

        learners.add(1);
        learners.add(2);

        electionManager = new ElectionManager();

        //ElectionManager electionManager = PowerMockito.mock(ElectionManager.class);
        //PowerMockito.when(electionManager.restoreLeaderElection(null, null)).thenReturn(null);
    }

    @After
    public void teardown() {
        File file = new File(metadataFile);
        file.delete();
    }

    @Test
    public void testOneMetadata() {
        try {
            electionMetadataManager = new ElectionMetadataManager(metadataFile, metadataPath);

            ElectionMetadata raftMetadata = ElectionMetadata.Build.create(metadataPath, raftTopic)
                    .electionType(PartitionGroup.ElectType.raft)
                    .allNodes(allNodes).learners(learners)
                    .localNode(localNodeId).currentTerm(1).votedFor(2).build();
            electionMetadataManager.updateElectionMetadata(raftTopic, raftMetadata);

            ElectionMetadata fixMetadata = ElectionMetadata.Build.create(metadataPath, fixTopic)
                    .electionType(PartitionGroup.ElectType.fix)
                    .allNodes(allNodes).learners(learners)
                    .localNode(localNodeId).leaderId(leaderId).build();
            electionMetadataManager.updateElectionMetadata(fixTopic, fixMetadata);
            electionMetadataManager.close();

            electionMetadataManager = new ElectionMetadataManager(metadataFile, metadataPath);
            electionMetadataManager.recover(electionManager);

            ElectionMetadata raftMetadataLoad = electionMetadataManager.getElectionMetadata(raftTopic);
            Assert.assertEquals(raftMetadataLoad.getCurrentTerm(), 1);
            Assert.assertEquals(raftMetadataLoad.getVotedFor(), 2);
            Assert.assertEquals(raftMetadataLoad.getAllNodes().size(), allNodes.size());
            Assert.assertEquals(raftMetadataLoad.getLocalNodeId(), localNodeId);
            Assert.assertEquals(raftMetadataLoad.getLearners().size(), learners.size());

            ElectionMetadata fixMetadataLoad = electionMetadataManager.getElectionMetadata(fixTopic);
            Assert.assertEquals(fixMetadataLoad.getAllNodes().size(), allNodes.size());
            Assert.assertEquals(fixMetadataLoad.getLocalNodeId(), localNodeId);
            Assert.assertEquals(fixMetadataLoad.getLeaderId(), leaderId);

            electionMetadataManager.removeElectionMetadata(raftTopic);
            electionMetadataManager.removeElectionMetadata(fixTopic);

            raftMetadata = electionMetadataManager.getElectionMetadata(raftTopic);
            Assert.assertNull(raftMetadata);
            fixMetadata = electionMetadataManager.getElectionMetadata(fixTopic);
            Assert.assertNull(fixMetadata);
        } catch (Exception e) {
            e.printStackTrace();
            Assert.fail();
        }

    }

    @Test
    public void testMultiMetadata() {
        try {
            electionMetadataManager = new ElectionMetadataManager(metadataFile, metadataPath);
            for (int i = 0; i < 100; i++) {
                TopicPartitionGroup partitionGroup = new TopicPartitionGroup("test", i);
                ElectionMetadata metadata = ElectionMetadata.Build.create(metadataPath, partitionGroup)
                        .electionType(PartitionGroup.ElectType.raft)
                        .allNodes(allNodes).learners(learners)
                        .localNode(localNodeId).currentTerm(i + 1).votedFor(i + 2).build();
                electionMetadataManager.updateElectionMetadata(partitionGroup, metadata);
            }
            electionMetadataManager.close();

            electionMetadataManager = new ElectionMetadataManager(metadataFile, metadataPath);
            electionMetadataManager.recover(electionManager);
            for (int i = 0; i < 100; i++) {
                TopicPartitionGroup partitionGroup = new TopicPartitionGroup("test", i);
                ElectionMetadata metadata = electionMetadataManager.getElectionMetadata(partitionGroup);
                Assert.assertEquals(metadata.getCurrentTerm(), i + 1);
                Assert.assertEquals(metadata.getVotedFor(), i + 2);
                Assert.assertEquals(metadata.getAllNodes().size(), allNodes.size());
                Assert.assertEquals(metadata.getLocalNodeId(), localNodeId);
                Assert.assertEquals(metadata.getLearners().size(), learners.size());

                electionMetadataManager.removeElectionMetadata(partitionGroup);
            }
            electionMetadataManager.close();
        } catch (Exception e) {
            Assert.fail();
        }
    }

}