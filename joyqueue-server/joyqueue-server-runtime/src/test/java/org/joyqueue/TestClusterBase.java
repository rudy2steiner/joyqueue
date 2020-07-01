package org.joyqueue;

import com.google.common.collect.Lists;
import org.joyqueue.broker.BrokerService;
import org.joyqueue.broker.config.Args;
import org.joyqueue.broker.config.ConfigDef;
import org.joyqueue.broker.consumer.Consume;
import org.joyqueue.broker.consumer.model.PullResult;
import org.joyqueue.broker.producer.Produce;

import org.joyqueue.broker.producer.PutResult;
import org.joyqueue.broker.protocol.JoyQueueCommandHandler;
import org.joyqueue.domain.*;
import org.joyqueue.exception.JoyQueueCode;
import org.joyqueue.helper.PortHelper;
import org.joyqueue.message.BrokerMessage;
import org.joyqueue.network.protocol.ProtocolService;
import org.joyqueue.network.session.Consumer;
import org.joyqueue.network.session.Producer;
import org.joyqueue.nsr.InternalServiceProvider;
import org.joyqueue.nsr.NameService;
import org.joyqueue.nsr.ServiceProvider;
import org.joyqueue.nsr.messenger.Messenger;
import org.joyqueue.plugin.SingletonController;
import org.joyqueue.store.StoreService;
import org.joyqueue.store.WriteResult;
import org.joyqueue.toolkit.io.Files;
import org.joyqueue.toolkit.network.IpUtil;
import org.joyqueue.toolkit.service.Service;
import org.joyqueue.toolkit.time.SystemClock;
import org.junit.*;

import java.io.File;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class TestClusterBase extends Service {

    private String DEFAULT_JOYQUEUE="joyqueue";
    private String ROOT_DIR =System.getProperty("java.io.tmpdir")+ File.separator+DEFAULT_JOYQUEUE;
    private int brokerPort=40088;
    int portInterval=10000;
    private List<BrokerService> brokers=new ArrayList<>();
    @Before
    public  void setup() throws Exception{
        // clean dir ,important
        Files.deleteDirectory(new File(ROOT_DIR));
        closeSingleton();
        launchCluster(2);
    }

    public  void closeSingleton() throws Exception{
        SingletonController.forceCloseSingleton();
        SingletonController.closeClassSingleton(Consume.class);
        SingletonController.closeClassSingleton(Produce.class);
        SingletonController.closeClassSingleton(NameService.class);
        SingletonController.closeClassSingleton(StoreService.class);
        SingletonController.closeClassSingleton(JoyQueueCommandHandler.class);
        SingletonController.closeClassSingleton(Messenger.class);
        SingletonController.closeClassSingleton(ProtocolService.class);
        SingletonController.closeClassSingleton(ServiceProvider.class);
        SingletonController.closeClassSingleton(InternalServiceProvider.class);
    }

    /**
     * Launch a N node cluster
     * @param N node num
     * @param port  broker port
     *
     **/
    public boolean launchCluster(int N, int port, int timeout, TimeUnit unit) throws Exception{
        String journalKeeperNodes = IpUtil.getLocalIp()+":"+String.valueOf(PortHelper.getJournalkeeperPort(port));
        for(int i=0;i<N;i++) {
            String rootDir=ROOT_DIR+File.separator+String.format("_%d",i);
            BrokerService broker=new BrokerService(args(port+i*portInterval,rootDir,journalKeeperNodes));
            CompletableFuture.runAsync(new Runnable() {
                @Override
                public void run() {
                    try{
                    broker.start();
                }catch (Exception e){
                    }
                }
            });
            brokers.add(broker);
        }
        Thread.sleep(5000);
        // wait cluster ready
        BrokerService b= brokers.get(0);
        long start= SystemClock.now();
        do {
            try {
                int cluster = b.getBrokerContext().getNameService().getAllBrokers().size();
                if (cluster >= N) {
                    break;
                }
            }catch (Exception e){

            }
            if (SystemClock.now() - start < unit.toMillis(timeout)) {
                Thread.sleep(1000);
            } else {
                throw new IllegalStateException("Start cluster timeout");
            }
        }while(true);
        return true;
    }

    /**
     * Launch multi broker
     **/
    public boolean launchCluster(int N) throws Exception{
        return launchCluster(N,brokerPort,30000,TimeUnit.MILLISECONDS);
    }


    /**
     * Build args
     **/
    public String[] args(int port,String applicationRoot,String journalKeeperNodes){
        Args args=new Args();
        args.append(ConfigDef.APPLICATION_DATA_PATH.key(),applicationRoot);
        args.append(ConfigDef.TRANSPORT_SERVER_PORT.key(),String.valueOf(port));
        args.append(ConfigDef.NAME_SERVER_JOURNAL_KEEPER_NODES.key(),journalKeeperNodes);
        return args.build();
    }


    /**
     * All broker
     **/
    public List<BrokerService> brokers(){
        return null;
    }

    @Ignore
    @Test
    public void testCreateTopic() throws Exception{
        createTopic("testCreateTopic",(short) 24);
    }

    /**
     * Create topic on random select broker
     **/
    public void createTopic(String topic, short partitions) throws Exception{
       NameService ns= nameService();
       Set<Short> partitionSet=new HashSet();
       for(short i=0;i<partitions;i++){
           partitionSet.add(i);
       }
       List<Broker> brokers=ns.getAllBrokers();
       if(brokers.size()<=0) throw new IllegalStateException("No broker!");
       brokers=brokers.size()<3?brokers: shuffle(brokers,3);
       Topic t=new Topic();
             t.setName(new TopicName(topic));
             t.setPartitions(partitions);
       PartitionGroup partitionGroup=new PartitionGroup();
       partitionGroup.setTopic(new TopicName(topic));
       partitionGroup.setGroup(0);
       partitionGroup.setPartitions(partitionSet);
       partitionGroup.setReplicas(brokers.stream().map(Broker::getId).collect(Collectors.toSet()));
       ns.addTopic(t, Lists.newArrayList(partitionGroup));
       // validate topic
       Assert.assertNotNull(ns.getTopicConfig(new TopicName(topic)));
    }

    /**
     *
     * Produce subscribe
     *
     **/
    public void produceSubscribe(String topic,String app) throws Exception{
        NameService ns= nameService();
        Subscription subscription=new Subscription();
        subscription.setTopic(new TopicName(topic));
        subscription.setApp(app);
        subscription.setType(Subscription.Type.PRODUCTION);
        ns.subscribe(subscription,ClientType.JOYQUEUE);
        Assert.assertNotNull(ns.getProducerByTopicAndApp(new TopicName(topic),app));
    }

    /**
     * Consume subscribe
     **/
    public void consumeSubscribe(String topic,String app) throws Exception{
        NameService ns= nameService();
        Subscription subscription=new Subscription();
        subscription.setTopic(new TopicName(topic));
        subscription.setApp(app);
        subscription.setType(Subscription.Type.CONSUMPTION);
        ns.subscribe(subscription,ClientType.JOYQUEUE);
        Assert.assertNotNull(ns.getConsumerByTopicAndApp(new TopicName(topic),app));
    }


    /**
     * Test send message
     **/
    @Ignore
    @Test
    public void testSendMessage() throws Exception{
        String topic="testSendMessage";
        String app="aaaaa";
        createTopic(topic,(short)24);
        produceSubscribe(topic,app);
        BrokerService leader=leader(topic,0);
        waitMetadataReady(leader,topic);
        Produce produce=leader.getBrokerContext().getProduce();
        int messagesCount=100;
        for(int i=0;i<messagesCount;i++) {
            sendMessage(produce,topic, app, "hello,test!", null);
        }
    }

    @Ignore
    @Test
    public void testLaunchCluster() throws Exception{
//        Assert.assertTrue(launchCluster(3));
          Thread.sleep(3600*1000);
    }

    @Test
    public void testProduceAndConsume() throws Exception{

        String topic="testProduceAndConsume";
        String app="aaaaa";
        createTopic(topic,(short)24);
        produceSubscribe(topic,app);
        BrokerService leader=leader(topic,0);
        waitMetadataReady(leader,topic);
        Produce produce=leader.getBrokerContext().getProduce();
        Consume consume=leader.getBrokerContext().getConsume();
        consumeSubscribe(topic,app);
        int messagesCount=100;
        for(int i=0;i<messagesCount;i++) {
            sendMessage(produce,topic, app, "hello,test!", null);
        }
        leader=leader(topic,0);
        waitMetadataReady(leader,topic);
        // consume
        consume(consume,topic,app);
    }

    public void consume(Consume consume,String topic,String app) throws Exception{
        Consumer consumer=new Consumer();
        consumer.setTopic(topic);
        consumer.setApp(app);
        consumer.setId("magic-id");
        int timeout=60*1000;
        PullResult pr=consume.getMessage(consumer,10,timeout);
        Assert.assertTrue(!pr.isEmpty());
    }


    /***
     * Wait metadata ready
     **/
    public void waitMetadataReady(BrokerService brokerService,String topic) throws Exception{
        int i=3;
        do {
            Thread.sleep(1000);
            List<Short> partitions = brokerService.getBrokerContext().getClusterManager().getMasterPartitionList(new TopicName(topic));
            if(partitions!=null){
                return;
            }
        }while(i-->0);
        throw new IllegalStateException(String.format("%s not ready",topic));
    }

    public void sendMessage(Produce produce,String topic,String app,String msg,String businessId) throws Exception{
          Producer producer=new Producer();
          producer.setTopic(topic);
          producer.setApp(app);
          //producer.setClientType(Cl);
          BrokerMessage bm= create(topic,app,msg,businessId);
          PutResult pr=produce.putMessage(producer,Lists.newArrayList(bm),QosLevel.REPLICATION);
          Assert.assertTrue(pr.getWriteResults().size()>0);
          for(WriteResult r:pr.getWriteResults().values()){
              Assert.assertTrue(r.getCode()== JoyQueueCode.SUCCESS);
          }
    }

    /**
     * Broker message
     **/
    public BrokerMessage create(String topic, String app, String msg,String businessId){
        BrokerMessage bm=new BrokerMessage();
        bm.setTopic(topic);
        bm.setApp(app);
        bm.setBody(msg.getBytes());
        bm.setBusinessId(businessId);
        bm.setClientIp(IpUtil.getLocalIp().getBytes());
        return bm;
    }

    /**
     * Random select n broker
     *
     **/
    public List<Broker> shuffle(List<Broker> brokers,int n){
        Collections.shuffle(brokers);
        return brokers.subList(0,n);
    }


    /**
     * Get Name service
     **/
    public NameService nameService() throws Exception{
        if(brokers.size()<=0) throw new IllegalStateException("no broker exist");
        BrokerService b= brokers.get(0);
        return b.getBrokerContext().getNameService();
    }


    public BrokerService leader(String topic,int partitionGroup) throws Exception{
        NameService ns=nameService();
        TopicConfig tc=ns.getTopicConfig(new TopicName(topic));
        PartitionGroup pg=tc.getPartitionGroups().get(partitionGroup);
        if(pg.getLeader()==null) throw new IllegalStateException("Leader not found");
        for(BrokerService broker:brokers){
            if(broker.getBrokerContext().getBroker().getId().equals(pg.getLeader())){
                return broker;
            }
        }
        return null;
    }


    /**
     * Cleanup cluster
     **/
    public void cleanupCluster(){
        for(BrokerService b:brokers){
            b.stop();
        }
        brokers.clear();
        Files.deleteDirectory(new File(ROOT_DIR));
    }

    @After
    public void close() throws Exception{
        for(BrokerService b:brokers){
            b.stop();
        }
        Files.deleteDirectory(new File(ROOT_DIR));
    }
}