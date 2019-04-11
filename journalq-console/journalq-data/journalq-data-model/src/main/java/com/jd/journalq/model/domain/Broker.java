package com.jd.journalq.model.domain;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;

/**
 * Broker实例
 *
 * @author tianya
 */
public class Broker extends BaseModel {
    public static final String DEFAULT_RETRY_TYPE="RemoteRetry";
    public static final String PERMISSION_FULL="FULL";
    public static final String PERMISSION_READ="READ";
    public static final String PERMISSION_WRITE="WRITE";
    public static final String PERMISSION_NONE="NONE";
    public static final String DEFAULT_PERMISSION=PERMISSION_FULL;
    /**
     * 数据中心Id
     */
    private Identity dataCenter;

    /**
     * 区域
     */
    private String region;
    /**
     * 主机id
     */
    private Identity host;
    /**
     * Broker实例的ip
     */
    @NotNull(message = "The ip can not be null")
    private String ip;
    /**
     * Broker实例的端口号
     */
    @NotNull(message = "The port can not be null")
    @Min(value = 100, message = "Please enter 100 to 65535")
    @Max(value = 65535, message = "Please enter 100 to 65535")
    private int port = 50088;
    /**
     * 分组
     */
    private Identity group;
    /**
     * 重试类型
     */
    private String retryType;
    /**
     * Broker实例的描述
     */
    private String description;

    private String permission=DEFAULT_PERMISSION;

    public Broker() {
        super();
    }


    public Broker(String ip, int port) {
        this.ip = ip;
        this.port = port;
    }

    public int getManagementPort() {
        return port % 10000 + 10000;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip.trim();
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    /**
     * @return 选举，复制都使用这个端口
     */
    public int getBackEndPort() {
        return port + 1;
    }

    /**
     * @return 监控服务端口
     * BrokerManage
     */
    public int getMonitorPort() {
        return port + 2;
    }

    /**
     * @return 监控服务端口
     * BrokerManage
     */
    public int getNameserverPort() {
        return port + 3;
    }


    public String getRetryType() {
        return retryType;
    }

    public void setRetryType(String retryType) {
        this.retryType = retryType;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Identity getDataCenter() {
        return dataCenter;
    }

    public void setDataCenter(Identity dataCenter) {
        this.dataCenter = dataCenter;
    }

    public Identity getHost() {
        return host;
    }

    public void setHost(Identity host) {
        this.host = host;
    }

    public Identity getGroup() {
        return group;
    }

    public void setGroup(Identity group) {
        this.group = group;
    }

    public String getPermission() {
        return permission;
    }

    public void setPermission(String permission) {
        this.permission = permission;
    }
}