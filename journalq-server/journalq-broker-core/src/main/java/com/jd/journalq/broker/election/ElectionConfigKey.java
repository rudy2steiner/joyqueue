package com.jd.journalq.broker.election;

import com.jd.journalq.toolkit.config.PropertyDef;

/**
 * author: zhuduohui
 * email: zhuduohui@jd.com
 * date: 2018/8/13
 */
public enum ElectionConfigKey implements PropertyDef {
    ELECTION_METADATA("election.metadata.file", "raft_metafile.dat", Type.STRING),
    ELECTION_TIMEOUT("election.election.timeout", 1000 * 6, Type.INT),
    EXECUTOR_THREAD_NUM_MIN("election.executor.thread.num.min", 5, Type.INT),
    EXECUTOR_THREAD_NUM_MAX("election.executor.thread.num.max", 50, Type.INT),
    TIMER_SCHEDULE_THREAD_NUM("election.timer.schedule.thread.num", 5, Type.INT),
    HEARTBEAT_TIMEOUT("election.heartbeat.timeout", 1000 * 1, Type.INT),
    SEND_COMMAND_TIMEOUT("election.send.command.timeout", 1000 * 5, Type.INT),
    MAX_BATCH_REPLICATE_SIZE("election.max.replicate.length", 1024 * 1024, Type.INT),
    DISABLE_STORE_TIMEOUT("election.disable.store.timeout", 1000 * 5, Type.INT),
    LISTEN_PORT("election.listen.port", 18001, Type.INT),
    TRANSFER_LEADER_TIMEOUT("election.transfer.leader.timeout", 1000 * 10, Type.INT),
    REPLICATE_CONSUME_POS_INTERVAL("election.replicate.consume.pos.interval", 1000 * 5, Type.INT),
    REPLICATE_THREAD_NUM_MIN("election.replicate.thread.num.min", 10, Type.INT),
    REPLICATE_THREAD_NUM_MAX("election.replicate.thread.num.max", 100, Type.INT),
    COMMAND_QUEUE_SIZE("election.command.queue.size", 1024, Type.INT),
    LOG_INTERVAL("election.log.interval", 3000, Type.INT);

    private String name;
    private Object value;
    private PropertyDef.Type type;

    ElectionConfigKey(String name, Object value, PropertyDef.Type type) {
        this.name = name;
        this.value = value;
        this.type = type;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Object getValue() {
        return value;
    }

    @Override
    public Type getType() {
        return type;
    }

}