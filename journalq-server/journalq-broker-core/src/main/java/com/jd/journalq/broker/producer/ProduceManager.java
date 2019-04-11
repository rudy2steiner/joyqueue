package com.jd.journalq.broker.producer;

import com.jd.journalq.broker.BrokerContext;
import com.jd.journalq.broker.BrokerContextAware;
import com.jd.journalq.broker.buffer.Serializer;
import com.jd.journalq.broker.cluster.ClusterManager;
import com.jd.journalq.broker.monitor.BrokerMonitor;
import com.jd.journalq.domain.PartitionGroup;
import com.jd.journalq.domain.QosLevel;
import com.jd.journalq.domain.TopicName;
import com.jd.journalq.exception.JMQCode;
import com.jd.journalq.exception.JMQException;
import com.jd.journalq.message.*;
import com.jd.journalq.network.session.Producer;
import com.jd.journalq.network.session.TransactionId;
import com.jd.journalq.store.PartitionGroupStore;
import com.jd.journalq.store.StoreService;
import com.jd.journalq.store.WriteRequest;
import com.jd.journalq.store.WriteResult;
import com.jd.journalq.toolkit.concurrent.EventListener;
import com.jd.journalq.toolkit.concurrent.LoopThread;
import com.jd.journalq.toolkit.lang.Close;
import com.jd.journalq.toolkit.lang.Preconditions;
import com.jd.journalq.toolkit.metric.Metric;
import com.jd.journalq.toolkit.service.Service;
import com.jd.journalq.toolkit.time.SystemClock;
import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 生产消息所依赖的服务,依赖
 * {@link TransactionManager}<br>
 * {@link ClusterManager}<br>
 * {@link StoreService}
 * <p>
 * author lining11 <br>
 * Date: 2018/8/17
 */
public class ProduceManager extends Service implements Produce, BrokerContextAware {

    private final static Logger logger = LoggerFactory.getLogger(ProduceManager.class);

    private ProduceConfig config;

    private TransactionManager transactionManager;

    private ClusterManager clusterManager;

    private StoreService store;

    private BrokerMonitor brokerMonitor;

    private BrokerContext brokerContext;

    private Metric metrics = null;
    private Metric.MetricInstance metric = null;
    private LoopThread metricThread = null;

    public ProduceManager() {
        //do nothing
    }

    public ProduceManager(ProduceConfig config, ClusterManager clusterManager, StoreService store, BrokerMonitor brokerMonitor) {
        this.config = config;
        this.clusterManager = clusterManager;
        this.store = store;
        this.brokerMonitor = brokerMonitor;
    }

    @Override
    protected void doStart() throws Exception {
        super.doStart();
        transactionManager.start();
        if(null != metricThread) {
            metricThread.start();
        }

        logger.info("ProduceManager is started.");
    }

    @Override
    protected void validate() throws Exception {
        super.validate();
        if (config == null) {
            config = new ProduceConfig(brokerContext == null ? null : brokerContext.getPropertySupplier());
        }
        if (store == null && brokerContext != null) {
            store = brokerContext.getStoreService();
        }
        if (clusterManager == null && brokerContext != null) {
            clusterManager = brokerContext.getClusterManager();
        }
        if (brokerMonitor == null && brokerContext != null) {
            brokerMonitor = brokerContext.getBrokerMonitor();
        }

        Preconditions.checkArgument(store != null, "store service can not be null");
        Preconditions.checkArgument(clusterManager != null, "cluster manager can not be null");

        if (brokerMonitor == null) {
            logger.warn("broker monitor is null.");
        }
        if (!clusterManager.isStarted()) {
            logger.warn("The clusterManager is not started, try to start it!");
            clusterManager.start();
        }
        transactionManager = new TransactionManager(config, store, clusterManager, brokerMonitor);

        if(config.getPrintMetricIntervalMs() > 0) {
            metrics = new Metric("input", 1, new String [] {"callback", "async"},new String[]{"tps"}, new String [] {"traffic"});
            metric = metrics.getMetricInstances().get(0);
            metricThread = LoopThread.builder()
                    .sleepTime(config.getPrintMetricIntervalMs(), config.getPrintMetricIntervalMs())
                    .name("Metric-Thread")
                    .onException(e -> logger.warn("Exception:", e))
                    .doWork(() -> metrics.reportAndReset()).build();
        }
    }

    @Override
    protected void doStop() {
        super.doStop();
        Close.close(transactionManager);
        if(null != metricThread) {
            metricThread.stop();
        }
        logger.info("ProduceManager is stopped.");
    }

    /**
     * 负责PutMessage命令的写入
     *
     * @param producer session 中生产者
     * @param msgs     要写入的消息
     * @author lining11
     * Date: 2018/8/17
     */
    public PutResult putMessage(Producer producer, List<BrokerMessage> msgs, QosLevel qosLevel) throws JMQException {
        // 超时时间
        int timeout = clusterManager.getProducerPolicy(TopicName.parse(producer.getTopic()), producer.getApp()).getTimeOut();
        return putMessage(producer, msgs, qosLevel, timeout);
    }

    public PutResult putMessage(Producer producer, List<BrokerMessage> msgs, QosLevel qosLevel, int timeout) throws JMQException {
        // 开始写入时间
        long startWritePoint = SystemClock.now();
        // 超时时间点
        long endTime = timeout + startWritePoint;

        if (config.getBrokerQosLevel() != -1) {
            qosLevel = QosLevel.valueOf(config.getBrokerQosLevel());
        }

        // 写入消息
        // 判断是否是事务消息
        String txId = msgs.get(0).getTxId();
        if (StringUtils.isNotEmpty(txId)) {
            return writeTxMessage(producer, msgs, txId, endTime);
        } else {
            return writeMessages(producer, msgs, qosLevel, endTime);
        }
    }

    /**
     * 异步写入消息
     *
     * @param producer session 中生产者
     * @param msgs     要写入的消息，如果是事务消息，则该批次的消息，必须都在同一个事务内，具有相同的txId
     * @param qosLevel 服务水平
     * @return
     * @throws JMQException
     */
    public void putMessageAsync(Producer producer, List<BrokerMessage> msgs, QosLevel qosLevel, EventListener<WriteResult> eventListener) throws JMQException {
        putMessageAsync(producer, msgs, qosLevel, clusterManager.getProducerPolicy(TopicName.parse(producer.getTopic()), producer.getApp()).getTimeOut(), eventListener);
    }

    @Override
    public void putMessageAsync(Producer producer, List<BrokerMessage> msgs, QosLevel qosLevel, int timeout, EventListener<WriteResult> eventListener) throws JMQException {
        // 开始写入时间
        long startWritePoint = SystemClock.now();
        // 超时时间点
        long endTime = timeout + startWritePoint;
        // 写入消息
        // 判断是否是事务消息
        String txId = msgs.get(0).getTxId();

        if (config.getBrokerQosLevel() != -1) {
            qosLevel = QosLevel.valueOf(config.getBrokerQosLevel());
        }

        if (StringUtils.isNotEmpty(txId)) {
            //TODO 超时时间计算错误
            writeTxMessageAsync(producer, msgs, txId, endTime, eventListener);
        } else {
            writeMessagesAsync(producer, msgs, qosLevel, endTime, eventListener);
        }
    }

    /**
     * 写入事务消息
     *
     * @param msgs
     * @param txId
     * @param endTime
     * @return
     * @throws JMQException
     */
    private PutResult writeTxMessage(Producer producer, List<BrokerMessage> msgs, String txId, long endTime) throws JMQException {
        ByteBuffer[] byteBuffers = generateRByteBufferList(msgs);
        Future<WriteResult> writeResultFuture = transactionManager.putMessage(producer, txId, byteBuffers);
        WriteResult writeResult = syncWait(writeResultFuture, endTime - SystemClock.now());
        PutResult putResult = new PutResult();
        putResult.addWriteResult(msgs.get(0).getPartition(), writeResult);
        return putResult;
    }

    /**
     * 异步写入事务消息
     *
     * @param msgs
     * @param txId
     * @param endTime
     * @return
     * @throws JMQException
     */
    private void writeTxMessageAsync(Producer producer, List<BrokerMessage> msgs, String txId, long endTime, EventListener<WriteResult> eventListener) throws JMQException {
        ByteBuffer[] byteBuffers = generateRByteBufferList(msgs);
        try {
            WriteResult writeResult = transactionManager.putMessage(producer, txId, byteBuffers).get(endTime, TimeUnit.MILLISECONDS);
            eventListener.onEvent(writeResult);
        } catch (Exception e) {
            logger.error("writeTxMessageAsync exception, producer: {}", producer, e);
            eventListener.onEvent(new WriteResult(JMQCode.CN_UNKNOWN_ERROR, ArrayUtils.EMPTY_LONG_ARRAY));
        }
    }

    /**
     * 写入消息
     *
     * @param producer
     * @param msgs
     * @param qosLevel
     * @param endTime
     * @return
     * @throws JMQException
     */
    private PutResult writeMessages(Producer producer, List<BrokerMessage> msgs, QosLevel qosLevel, long endTime) throws JMQException {
        PutResult putResult = new PutResult();
        String topic = producer.getTopic();
        List<Short> partitions = clusterManager.getMasterPartitionList(TopicName.parse(topic));
        if (partitions == null || partitions.size() == 0) {
            logger.error("no partitions available topic:%s", topic);
            throw new JMQException(JMQCode.CN_NO_PERMISSION);
        }
        long startTime = SystemClock.now();
        // 分配消息对于的分区分组
        Map<PartitionGroup, List<WriteRequest>> dispatchedMsgs = dispatchPartition(msgs, partitions);
        // 分区分组集合
        Set<PartitionGroup> partitionGroupSet = dispatchedMsgs.keySet();
        PartitionGroup oneGroup = partitionGroupSet.stream().findFirst().get();
        // 服务水平级别
        for (PartitionGroup partitionGroup : partitionGroupSet) {
            PartitionGroupStore partitionStore = store.getStore(topic, partitionGroup.getGroup(), qosLevel);
            List<WriteRequest> writeRequests = dispatchedMsgs.get(partitionGroup);
            // 异步写入磁盘
            Future<WriteResult> writeResultFuture = partitionStore.asyncWrite(writeRequests.toArray(new WriteRequest[]{}));
            // 同步等待写入完成
            WriteResult writeResult = syncWait(writeResultFuture, endTime - SystemClock.now());
            // 构造写入结果
            writeRequests.forEach(writeRequest -> {
                putResult.addWriteResult(writeRequest.getPartition(), writeResult);
                if (brokerMonitor != null) {
                    brokerMonitor.onPutMessage(topic, producer.getApp(), partitionGroup.getGroup(), writeRequest.getPartition(), 1, writeRequest.getBuffer().limit(), SystemClock.now() - startTime);
                }
            });
        }

        return putResult;
    }
    /**
     * 异步写入消息
     *
     * @param producer
     * @param msgs
     * @param qosLevel
     * @param endTime
     * @return
     * @throws JMQException
     */
    private void writeMessagesAsync(Producer producer, List<BrokerMessage> msgs, QosLevel qosLevel, long endTime, EventListener<WriteResult> eventListener) throws JMQException {
        String topic = producer.getTopic();
        String app = producer.getApp();
        List<Short> partitions = clusterManager.getMasterPartitionList(TopicName.parse(topic));
        if (partitions == null || partitions.size() == 0) {
            logger.error("no partitions available topic:%s", topic);
            throw new JMQException(JMQCode.CN_NO_PERMISSION);
        }
        // 分配消息对于的分区分组
        Map<PartitionGroup, List<WriteRequest>> dispatchedMsgs = dispatchPartition(msgs, partitions);
        // 分区分组集合
        Set<PartitionGroup> partitionGroupSet = dispatchedMsgs.keySet();
        PartitionGroup oneGroup = partitionGroupSet.stream().findFirst().get();
        // 服务水平级别
        for (PartitionGroup partitionGroup : partitionGroupSet) {
            if (logger.isDebugEnabled()) {
                logger.debug("ProduceManager writeMessageAsync topic:[{}], partitionGroup:[{}]]", topic, partitionGroup);
            }
            PartitionGroupStore partitionStore = store.getStore(topic, partitionGroup.getGroup(), qosLevel);
            List<WriteRequest> writeRequests = dispatchedMsgs.get(partitionGroup);
            // 异步写入磁盘
            if(null != metric) {
                long t0 = System.nanoTime();
                partitionStore.asyncWrite(new MetricEventListener(t0, metric, eventListener, topic, app, partitionGroup.getGroup(), writeRequests), writeRequests.toArray(new WriteRequest[]{}));

                long t1 = System.nanoTime();
                metric.addCounter("tps", writeRequests.stream().map(WriteRequest::getBuffer).count());
                metric.addTraffic("traffic", writeRequests.stream().map(WriteRequest::getBuffer).mapToInt(ByteBuffer::remaining).sum());
                metric.addLatency("async", t1 - t0);
            } else {
                long startTime = SystemClock.now();
                partitionStore.asyncWrite(event -> {
                    writeRequests.forEach(writeRequest -> {
                        brokerMonitor.onPutMessage(topic, app, partitionGroup.getGroup(), writeRequest.getPartition(), 1, writeRequest.getBuffer().limit(), SystemClock.now() - startTime);
                    });
                    eventListener.onEvent(event);
                }, writeRequests.toArray(new WriteRequest[]{}));
            }
        }
    }

    class MetricEventListener implements EventListener<WriteResult> {
        final long t0;
        final Metric.MetricInstance metric;
        final EventListener<WriteResult> eventListener;
        final String topic;
        final String app;
        final int partitionGroup;
        final List<WriteRequest> writeRequests;

        MetricEventListener(long t0, Metric.MetricInstance metric, EventListener<WriteResult> eventListener, String topic, String app, int partitionGroup, List<WriteRequest> writeRequests) {

            this.t0 = t0;
            this.metric = metric;
            this.eventListener = eventListener;
            this.topic = topic;
            this.app = app;
            this.partitionGroup = partitionGroup;
            this.writeRequests = writeRequests;
        }
        @Override
        public void onEvent(WriteResult event) {
            metric.addLatency("callback",System.nanoTime() - t0);
            eventListener.onEvent(event);

            writeRequests.forEach(writeRequest -> {
                brokerMonitor.onPutMessage(topic, app, partitionGroup, writeRequest.getPartition(), 1, writeRequest.getBuffer().limit(), SystemClock.now() - t0/1000000);
            });

        }
    }

    /**
     * 同步等待
     *
     * @param writeResultFuture
     * @param timeout
     * @return
     * @throws JMQException
     */
    private WriteResult syncWait(Future<WriteResult> writeResultFuture, long timeout) throws JMQException {
        try {
            return writeResultFuture.get(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException | ExecutionException e) {
            logger.error("Write message error", e);
            throw new JMQException(JMQCode.CN_THREAD_INTERRUPTED);
        } catch (TimeoutException e) {
            throw new JMQException(JMQCode.SE_WRITE_TIMEOUT);
        }
    }

    /**
     * 按照partitionGroup分配写入请求
     *
     * @param messageList
     * @param partitionList
     * @return
     * @throws JMQException
     */
    private Map<PartitionGroup, List<WriteRequest>> dispatchPartition(List<BrokerMessage> messageList, List<Short> partitionList) throws JMQException {
        // 随机指定一个写入分区
        int index = (int) Math.floor(Math.random() * partitionList.size());
        short partition = partitionList.get(index);
        // 分区数量
        final int partitionSize = partitionList.size();

        Map<PartitionGroup, List<WriteRequest>> resultMap = new HashMap<>();
        for (BrokerMessage msg : messageList) {
            // 选择一个分区
            short writePartition = selectPartition(partition, msg, partitionList, partitionSize);
            // 拿到分区分组
            PartitionGroup writePartitionGroup = clusterManager.getPartitionGroup(TopicName.parse(msg.getTopic()), writePartition);
            // 按照分区分组分别组装
            List<WriteRequest> writeRequestList = resultMap.get(writePartitionGroup);
            if (writeRequestList == null) {
                writeRequestList = new ArrayList<>();
                resultMap.put(writePartitionGroup, writeRequestList);
            }
            writeRequestList.add(new WriteRequest(writePartition, convertBrokerMessage2RByteBuffer(msg)));
        }

        return resultMap;
    }

    /**
     * 选择写入分区
     *
     * @param defaultPartition
     * @param msg
     * @param partitionList
     * @param partitionSize
     * @return
     */
    private short selectPartition(short defaultPartition, BrokerMessage msg, List<Short> partitionList, int partitionSize) {
        // 如果指定分区，则使用指定分区（判断标准：partition >= 0）
        if (msg.getPartition() >= 0) {
            return msg.getPartition();
        }
        short writePartition = defaultPartition;
        if (msg.isOrdered()) {
            // 顺序消息，根据业务ID指定分区
            String businessId = msg.getBusinessId();
            if (StringUtils.isEmpty(businessId)) {
                // 业务ID如果为空，则指定写第一个分区
                writePartition = partitionList.get(0);
            } else {
                int hashCode = businessId.hashCode();
                hashCode = hashCode > Integer.MIN_VALUE ? hashCode : Integer.MIN_VALUE + 1;
                int orderIndex = Math.abs(hashCode) % partitionSize;
                writePartition = partitionList.get(orderIndex);
            }
        }
        return writePartition;
    }

    /**
     * 将BrokerMessage转换成RByteBuffer
     *
     * @param brokerMessage
     * @return
     * @throws JMQException
     */
    private ByteBuffer convertBrokerMessage2RByteBuffer(BrokerMessage brokerMessage) throws JMQException {
        int msgSize = Serializer.sizeOf(brokerMessage);
        // todo bufferPool有问题，暂时直接创建
        ByteBuffer allocate = ByteBuffer.allocate(msgSize);
        try {
            Serializer.write(brokerMessage, allocate, msgSize);
        } catch (Exception e) {
            logger.error("Serialize message error! topic:{},app:{}", brokerMessage.getTopic(), brokerMessage.getApp(), e);
            throw new JMQException(JMQCode.SE_SERIALIZER_ERROR);
        }
        return allocate;
    }

    /**
     * @param msgs
     * @return
     * @throws JMQException
     */
    private ByteBuffer[] generateRByteBufferList(List<BrokerMessage> msgs) throws JMQException {
        int size = msgs.size();
        ByteBuffer[] byteBuffers = new ByteBuffer[size];
        for (int i = 0; i < size; i++) {
            BrokerMessage message = msgs.get(i);
            ByteBuffer byteBuffer = convertBrokerMessage2RByteBuffer(message);
            byteBuffers[i] = byteBuffer;
        }
        return byteBuffers;
    }


    /**
     * 负责非包含消息内容的命令写入，包括(TxPrepare,TxCommit,TxRollback)
     *
     * @param tx 事务消息命令
     */
    public TransactionId putTransactionMessage(Producer producer, JournalLog tx) throws JMQException {
        if (tx.getType() == JournalLog.TYPE_TX_PREPARE) {
            return transactionManager.prepare(producer, (BrokerPrepare) tx);
        } else if (tx.getType() == JournalLog.TYPE_TX_COMMIT) {
            return transactionManager.commit(producer, (BrokerCommit) tx);
        } else if (tx.getType() == JournalLog.TYPE_TX_ROLLBACK) {
            return transactionManager.rollback(producer, (BrokerRollback) tx);
        } else {
            throw new JMQException(JMQCode.CN_COMMAND_UNSUPPORTED);
        }
    }

    @Override
    public List<TransactionId> getFeedback(Producer producer, int count) {
        return transactionManager.getFeedback(producer, count);
    }

    @Override
    public void setBrokerContext(BrokerContext brokerContext) {
        this.brokerContext = brokerContext;
    }
}