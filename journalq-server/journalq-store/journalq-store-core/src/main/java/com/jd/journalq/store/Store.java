package com.jd.journalq.store;

import com.jd.journalq.domain.QosLevel;
import com.jd.journalq.store.file.PositioningStore;
import com.jd.journalq.store.replication.ReplicableStore;
import com.jd.journalq.store.transaction.TransactionStore;
import com.jd.journalq.store.transaction.TransactionStoreManager;
import com.jd.journalq.store.utils.PreloadBufferPool;
import com.jd.journalq.toolkit.concurrent.NamedThreadFactory;
import com.jd.journalq.toolkit.config.PropertySupplier;
import com.jd.journalq.toolkit.config.PropertySupplierAware;
import com.jd.journalq.toolkit.lang.Close;
import com.jd.journalq.toolkit.service.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 *
 * @author liyue25
 * Date: 2018/8/13
 *
 * root                            # 数据文件根目录
 * ├── metadata                    # 元数据，目前只存放brokerId
 * ├── lock                        # 进程锁目录，避免多进程同时操作导致数据损坏
 * │   └── 112334                  # 当前持有锁的进程的PID
 * └── topics                      # 所有topic目录，子目录就是topic名称
 *     ├── coupon                  # topic coupon
 *     └── order                   # topic order
 *         ├── 1                   # topic group
 *         │   ├── checkpoint      # 检查点文件
 *         │   ├── index           # 索引文件目录，每个子目录为partition，目录名称就是partitionId
 *         │   │   ├── 3           # partition 3，目录下的文件为该partition的索引文件
 *         │   │   │   ├── 1048576 # 索引文件，固定文件长度，文件名为文件记录的第一条消息索引在Partition中的序号。
 *         │   │   │   └── 2097152
 *         │   │   ├── 4
 *         │   │   └── 5
 *         │   ├── 0               # 消息日志文件
 *         │   ├── 134217728
 *         │   └── 268435456
 *         ├── tx                  # 事务消息目录，存放未提交的事务消息
 *         │   ├── 0
 *         │   ├── 1
 *         │   └── 2
 *         └── subscription        # 订阅文件，记录所有订阅和消费指针
 *
 *
 */
public class Store extends Service implements StoreService, Closeable, PropertySupplierAware {

    private final static Logger logger = LoggerFactory.getLogger(Store.class);
    private final static int SCHEDULE_EXECUTOR_THREADS = 16;



    private final static String TOPICS_DIR = "topics";
    private final static String TX_DIR = "tx";
    private final static String DEL_PREFIX= ".d.";
    private  StoreConfig config ;
    private  PreloadBufferPool bufferPool;
    /**
     * key: [topic]/[group index]，例如：order/1
     */
    private final Map<String,PartitionGroupStoreManager> storeMap = new HashMap<>();
    private final Map<String,TransactionStoreManager> txStoreMap = new HashMap<>();

    private File base;
    private ScheduledExecutorService scheduledExecutor;
    private PropertySupplier propertySupplier;

    public Store() {
        //do nothing
    }

    public Store(StoreConfig config) {
        this.config = config;
    }

    private StoreLock storeLock;


    @Override
    protected void validate() throws Exception {
        super.validate();
        if (config == null){
            config = new StoreConfig(propertySupplier);
        }
        if (base == null){
            base = new File(config.getPath());
        }
        checkOrCreateBase();
        if (storeLock ==null){
            storeLock = new StoreLock(new File(base,"lock"));
            storeLock.lock();
        }
        if (scheduledExecutor == null){
            this.scheduledExecutor = Executors.newScheduledThreadPool(SCHEDULE_EXECUTOR_THREADS, new NamedThreadFactory("Store-Scheduled-Executor"));
        }
        if (bufferPool == null){
            this.bufferPool = new PreloadBufferPool(config.getPrintMetricIntervalMs());
        }
        this.bufferPool.addPreLoad(config.getIndexFileSize(), config.getPreLoadBufferCoreCount(), config.getPreLoadBufferMaxCount());
        this.bufferPool.addPreLoad(config.getMessageFileSize(), config.getPreLoadBufferCoreCount(), config.getPreLoadBufferMaxCount());

    }

    @Override
    protected void doStart() throws Exception {
        super.doStart();
        logger.info("Starting store {}...", base.getPath());
        for(PartitionGroupStoreManager manger: storeMap.values()){
            if(!manger.isStarted()) manger.start();
        }


        started.set(true);
        logger.info("Store started.");
    }

    @Override
    protected void doStop() {
        super.doStop();

        Close.close(scheduledExecutor);

        logger.info("Stopping store {}...", base.getPath());
        System.out.println(String.format("Stopping store %s...", base.getPath()));
        storeMap.values().forEach(p -> {
            p.disable(5000L);
            p.stop();
        });
        if(null != scheduledExecutor && !scheduledExecutor.isTerminated()) {
            System.out.println("Shutting down executor...");
            logger.info("Shutting down executor...");
            scheduledExecutor.shutdown();
            try {
                scheduledExecutor.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                logger.warn("Exception: ", e);
                e.printStackTrace();
            }
            if (!scheduledExecutor.isTerminated()) scheduledExecutor.shutdownNow();
        }
        logger.info("Executor stopped.");
        System.out.println("Executor stopped.");
        started.set(false);
        logger.info("Store stopped.");
        System.out.println("Store stopped.");
    }

    public void checkOrCreateBase() {
        if(!base.exists()){
            if( !base.mkdirs()) throw  new StoreInitializeException();
        } else {
            if(!base.isDirectory()) throw new StoreInitializeException();
        }
    }





    public boolean physicalDelete(){
        if(started.get()) {
            logger.info("Stop me fist!");
            return false;
        } else {
            logger.info("PHYSICAL DELETE {}...",base.getAbsolutePath());
            deleteFolder(base);
            return true;
        }
    }

    @Override
    public boolean partitionGroupExists(String topic, int partitionGroup) {
        return new File(base, getPartitionGroupRelPath(topic,partitionGroup)).isDirectory();
    }

    @Override
    public boolean topicExists(String topic) {
        return new File(base, getTopicRelPath(topic)).isDirectory();
    }

    @Override
    public TransactionStore getTransactionStore(String topic) {
        synchronized (txStoreMap) {
            if (txStoreMap.containsKey(topic)) {
                return txStoreMap.get(topic);
            } else {
                File txBase = new File(new File(base, getTopicRelPath(topic)), TX_DIR);
                if(topicExists(topic) && (txBase.isDirectory() || txBase.mkdirs())) {
                    TransactionStoreManager transactionStore =
                            new TransactionStoreManager(txBase,
                                    getMessageStoreConfig(config),bufferPool);
                    txStoreMap.put(topic, transactionStore);
                    return transactionStore;
                } else {
                    return null;
                }
            }
        }


    }

    @Override
    public List<TransactionStore> getAllTransactionStores() {
        return this.storeMap.keySet().stream()
                .map(key -> key.replaceAll("^(.*)/\\d+$","$1"))
                .distinct()
                .map(topic -> {
                    synchronized (txStoreMap) {
                        if (txStoreMap.containsKey(topic)) {
                            return txStoreMap.get(topic);
                        } else {
                            File txBase = new File(new File(base, getTopicRelPath(topic)), TX_DIR);
                            if(txBase.isDirectory()) {
                                TransactionStoreManager transactionStore = new TransactionStoreManager(txBase,
                                        getMessageStoreConfig(config),bufferPool);
                                txStoreMap.put(topic, transactionStore);
                                return transactionStore;
                            } else {
                                return null;
                            }
                        }
                    }
                }).filter(Objects::nonNull).collect(Collectors.toList());
    }

    @Override
    public synchronized void removePartitionGroup(String topic, int partitionGroup) {
        PartitionGroupStoreManager partitionGroupStoreManger = storeMap.remove(topic + "/" + partitionGroup);
        if(null != partitionGroupStoreManger) {
            partitionGroupStoreManger.stop();
            partitionGroupStoreManger.close();
        }
        File groupBase = new File(base, getPartitionGroupRelPath(topic,partitionGroup));

        if(groupBase.exists()) delete(groupBase);



        File topicBase = new File(base,getTopicRelPath(topic));

        File [] files = topicBase.listFiles((dir, name) -> name.matches("\\d+"));
        if(null == files || files.length == 0) {
            synchronized (txStoreMap) {
                if(txStoreMap.containsKey(topic)) {
                    TransactionStoreManager transactionStore = txStoreMap.remove(topic);
                    if (null != transactionStore) {
                        transactionStore.close();
                    }
                }
            }
            delete(topicBase);
        }

    }


    @Override
    public synchronized void restorePartitionGroup(String topic, int partitionGroup) throws Exception {

        PartitionGroupStoreManager partitionGroupStoreManger = partitionGroupStore(topic,partitionGroup);
        if(null == partitionGroupStoreManger) {
            File groupBase = new File(base, getPartitionGroupRelPath(topic,partitionGroup));
            partitionGroupStoreManger = new PartitionGroupStoreManager(topic,partitionGroup,groupBase
                    ,getPartitionGroupConfig(config)
                    , bufferPool
                    , scheduledExecutor);
            partitionGroupStoreManger.recover();
            if(isStarted()) {
                partitionGroupStoreManger.start();
            }
            storeMap.put(topic + "/" + partitionGroup, partitionGroupStoreManger);

        }
    }

    @Override
    public synchronized void createPartitionGroup(String topic, int partitionGroup, short [] partitions, int[] nodes) throws Exception {
        // TODO: 检查topic名称
        if(!storeMap.containsKey(topic + "/" + partitionGroup)) {
            File groupBase = new File(base, getPartitionGroupRelPath(topic, partitionGroup));
            if(groupBase.exists()) delete(groupBase);
            PartitionGroupStoreSupport.init(groupBase, partitions);

            restorePartitionGroup(topic, partitionGroup);
        }
    }


    private PartitionGroupStoreManager.Config getPartitionGroupConfig(StoreConfig config) {

        PositioningStore.Config messageConfig = getMessageStoreConfig(config);
        PositioningStore.Config indexConfig = new PositioningStore.Config(config.getIndexFileSize(),
                config.getFileHeaderSize());
        return new PartitionGroupStoreManager.Config(
                config.getMaxMessageLength(), config.getWriteRequestCacheSize(), config.getFlushIntervalMs(),
                config.getWriteTimeoutMs(), config.getMaxDirtySize(),
                config.getPrintMetricIntervalMs(), messageConfig,indexConfig);
    }

    private PositioningStore.Config getMessageStoreConfig(StoreConfig config) {
        return new PositioningStore.Config(config.getMessageFileSize(),
                config.getFileHeaderSize());
    }

    /**
     * 并不真正删除，只是重命名
     */
    private boolean delete(File file) {
        File renamed = new File(file.getParent(),DEL_PREFIX + System.currentTimeMillis() + "." + file.getName());
        return file.renameTo(renamed);
    }

    @Override
    public PartitionGroupStore getStore(String topic, int partitionGroup, QosLevel writeQosLevel) {
        return partitionGroupStore(topic, partitionGroup).getQosStore(writeQosLevel);
    }
    @Override
    public PartitionGroupStore getStore(String topic, int partitionGroup) {
        return getStore(topic,partitionGroup,QosLevel.PERSISTENCE);
    }
    @Override
    public List<PartitionGroupStore> getStore(String topic) {
        return partitionGroupStores(topic).stream().map(p -> p.getQosStore(QosLevel.PERSISTENCE)).collect(Collectors.toList());
    }

    @Override
    public void rePartition(String topic, int partitionGroup, Short[] partitions) throws IOException {
        PartitionGroupStoreManager partitionGroupStoreManger = partitionGroupStore(topic,partitionGroup);
        if(null != partitionGroupStoreManger) {
            partitionGroupStoreManger.rePartition(partitions);
        } else {
            throw new NoSuchPartitionGroupException();
        }
    }

    /**
     * 获取用于选举复制的存储
     *
     */
    @Override
    public ReplicableStore getReplicableStore(String topic, int partitionGroup) {
        return partitionGroupStore(topic,partitionGroup);
    }

    /**
     * 获取管理接口
     */
    @Override
    public StoreManagementService getManageService() {

        return new StoreManagement(128, 128, config.getMaxMessageLength(), bufferPool, this);
    }


    @Override
    public synchronized void stop() {
        logger.info("Stopping store {}...", base.getPath());
        System.out.println(String.format("Stopping store %s...", base.getPath()));
        storeMap.values().forEach(p -> {
            p.disable(5000L);
            p.stop();
        });
        if(null != scheduledExecutor && !scheduledExecutor.isTerminated()) {
            System.out.println("Shutting down executor...");
            logger.info("Shutting down executor...");
            scheduledExecutor.shutdown();
            try {
                scheduledExecutor.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                logger.warn("Exception: ", e);
                e.printStackTrace();
            }
            if (!scheduledExecutor.isTerminated()) scheduledExecutor.shutdownNow();
        }
        logger.info("Executor stopped.");
        System.out.println("Executor stopped.");
        started.set(false);
        logger.info("Store stopped.");
        System.out.println("Store stopped.");
    }


    @Override
    public boolean isStarted() {
        return started.get();
    }

    private String getPartitionGroupRelPath(String topic, int partitionGroup){
        return TOPICS_DIR + File.separator + topic.replace('/', '@') + File.separator + partitionGroup;
    }

    private String getTopicRelPath(String topic){
        return TOPICS_DIR + File.separator + topic;
    }


    File base() {
        return base;
    }


    // TODO: 在哪儿调用呢？
    @Override
    public void close() throws IOException {
        for(PartitionGroupStoreManager p: storeMap.values()) {
            p.close();
        }
    }

    private void deleteFolder(File folder) {
        File[] files = folder.listFiles();
        if(files!=null) {
            for(File f: files) {
                if(f.isDirectory()) {
                    deleteFolder(f);
                } else {
                    if(!f.delete()) {
                        logger.warn("Delete failed: {}", f.getAbsolutePath());
                    }
                }
            }
        }
        if(!folder.delete()) {
            logger.warn("Delete failed: {}", folder.getAbsolutePath());

        }
    }


    List<String> topics() {
        return storeMap.keySet().stream().map(k->k.split("/")[0]).distinct().collect(Collectors.toList());
    }

    List<String> partitionGroups() {
        return new ArrayList<>(storeMap.keySet());
    }

    List<Integer> partitionGroups(String topic) {
        return storeMap.keySet().stream()
                .filter(k -> k.startsWith(topic + "/"))
                .map(k->k.substring(topic.length() + 1))
                .map(Integer::parseInt)
                .collect(Collectors.toList());
    }

    PartitionGroupStoreManager partitionGroupStore(String topic, int partitionGroup) {
        return storeMap.get(topic + "/" + partitionGroup);
    }

    List<PartitionGroupStoreManager> partitionGroupStores(String topic) {
        return partitionGroups(topic).stream().map(id -> storeMap.get(topic + "/" + id)).collect(Collectors.toList());
    }

    @Override
    public void setSupplier(PropertySupplier supplier) {
        this.propertySupplier = supplier;
        if (config == null){
            config = new StoreConfig(supplier);
        }
    }
}