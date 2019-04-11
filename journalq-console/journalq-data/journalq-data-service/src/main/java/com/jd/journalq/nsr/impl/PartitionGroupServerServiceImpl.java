package com.jd.journalq.nsr.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.jd.journalq.domain.PartitionGroup;
import com.jd.journalq.model.PageResult;
import com.jd.journalq.model.QPageQuery;
import com.jd.journalq.convert.NsrPartitionGroupConverter;
import com.jd.journalq.model.domain.Namespace;
import com.jd.journalq.model.domain.OperLog;
import com.jd.journalq.model.domain.Topic;
import com.jd.journalq.model.domain.TopicPartitionGroup;
import com.jd.journalq.model.query.QTopicPartitionGroup;
import com.jd.journalq.nsr.model.PartitionGroupQuery;
import com.jd.journalq.nsr.NameServerBase;
import com.jd.journalq.nsr.PartitionGroupServerService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Created by wangxiaofei1 on 2019/1/3.
 */
@Service("partitionGroupServerService")
public class PartitionGroupServerServiceImpl extends NameServerBase implements PartitionGroupServerService {
    public static final String ADD_PARTITIONGROUP="/partitiongroup/add";
    public static final String REMOVE_PARTITIONGROUP="/partitiongroup/remove";
    public static final String UPDATE_PARTITIONGROUP="/partitiongroup/update";
    public static final String LIST_PARTITIONGROUP="/partitiongroup/list";
    public static final String GETBYID_PARTITIONGROUP="/partitiongroup/getById";
    public static final String FINDBYQUERY_PARTITIONGROUP="/partitiongroup/findByQuery";
    private NsrPartitionGroupConverter nsrPartitionGroupConverter = new NsrPartitionGroupConverter();

    @Override
    public TopicPartitionGroup findById(String s) throws Exception {
        String result = post(GETBYID_PARTITIONGROUP,s);
        PartitionGroup partitionGroup = JSON.parseObject(result,PartitionGroup.class);
        return nsrPartitionGroupConverter.revert(partitionGroup);
    }

    @Override
    public PageResult<TopicPartitionGroup> findByQuery(QPageQuery<QTopicPartitionGroup> query) throws Exception {
        PartitionGroupQuery partitionGroupQuery =null;
        if (query != null && query.getQuery() != null) {
            QTopicPartitionGroup queryQuery = query.getQuery();
            partitionGroupQuery = new PartitionGroupQuery(queryQuery.getTopic().getCode(),queryQuery.getNamespace().getCode());
            partitionGroupQuery.setKeyword(query.getQuery().getKeyword());
        }
        QPageQuery<PartitionGroupQuery> partitionGroupQueryQPageQuery = new QPageQuery<>(query.getPagination(),partitionGroupQuery);

        String result = post(FINDBYQUERY_PARTITIONGROUP,partitionGroupQueryQPageQuery);

        PageResult<PartitionGroup> partitionGroupPageResult = JSON.parseObject(result,new TypeReference<PageResult<PartitionGroup>>(){});
        if (partitionGroupPageResult == null || partitionGroupPageResult.getResult() == null) {
            return PageResult.empty();
        }
        return new PageResult<>(partitionGroupPageResult.getPagination(), partitionGroupPageResult.getResult().stream().map(partitionGroup -> nsrPartitionGroupConverter.revert(partitionGroup)).collect(Collectors.toList()));

    }

    @Override
    public int delete(TopicPartitionGroup model) throws Exception {
        PartitionGroup partitionGroup = nsrPartitionGroupConverter.convert(model);
        String result = postWithLog(REMOVE_PARTITIONGROUP,partitionGroup, OperLog.Type.PARTITION_GROUP.value(),OperLog.OperType.DELETE.value(),partitionGroup.getTopic().getCode());
        return isSuccess(result);
    }

    @Override
    public int add(TopicPartitionGroup model) throws Exception {
        PartitionGroup partitionGroup = nsrPartitionGroupConverter.convert(model);
        String result = postWithLog(ADD_PARTITIONGROUP,partitionGroup,OperLog.Type.PARTITION_GROUP.value(),OperLog.OperType.ADD.value(),partitionGroup.getTopic().getCode());
        return isSuccess(result);
    }

    @Override
    public int update(TopicPartitionGroup model) throws Exception {
        PartitionGroup partitionGroup = nsrPartitionGroupConverter.convert(model);
        String result = postWithLog(UPDATE_PARTITIONGROUP,partitionGroup,OperLog.Type.PARTITION_GROUP.value(),OperLog.OperType.UPDATE.value(),partitionGroup.getTopic().getCode());
        return isSuccess(result);
    }

    @Override
    public List<TopicPartitionGroup> findByQuery(QTopicPartitionGroup query) throws Exception {
        PartitionGroupQuery partitionGroupQuery =null;
        if (query != null) {
            partitionGroupQuery = new PartitionGroupQuery();
            if (query.getTopic() != null) {
                partitionGroupQuery.setTopic(query.getTopic().getCode());
            }
            if (query.getNamespace() != null) {
                partitionGroupQuery.setNamespace(query.getNamespace().getCode());
            }
            if (query.getGroup() != null) {
                partitionGroupQuery.setGroup(query.getGroup());
            }
        }
        String result = post(LIST_PARTITIONGROUP,partitionGroupQuery);
        List<PartitionGroup> replicas = JSON.parseArray(result,PartitionGroup.class);
        if (replicas == null || replicas.size() <=0) {
            return null;
        }
        return replicas.stream().map(partitionGroup -> nsrPartitionGroupConverter.revert(partitionGroup)).collect(Collectors.toList());
    }

    @Override
    public List<TopicPartitionGroup> findByTopic(String topic) {
        QTopicPartitionGroup qTopicPartitionGroup = new QTopicPartitionGroup(new Topic(topic));
        try {
            return findByQuery(qTopicPartitionGroup);
        } catch (Exception e) {
            logger.error("findByTopic exception",e);
        }
        return null;
    }

    @Override
    public TopicPartitionGroup findByTopicAndGroup(String namespace, String topic, Integer groupNo) {
        QTopicPartitionGroup qTopicPartitionGroup = new QTopicPartitionGroup(new Topic(topic),new Namespace(namespace),groupNo);
        try {
            List<TopicPartitionGroup> topicPartitionGroups = findByQuery(qTopicPartitionGroup);
            if (topicPartitionGroups == null || topicPartitionGroups.size() <= 0) {
                return null;
            }
            return topicPartitionGroups.get(0);
        } catch (Exception e) {
            logger.error("findByTopicAndGroup exception",e);
        }
        return null;
    }

    @Override
    public List<TopicPartitionGroup> findByTopic(String topic, String namespace) {

        QTopicPartitionGroup qTopicPartitionGroup=new QTopicPartitionGroup();
        Topic topiC=new Topic(topic);
        Namespace namespacE=new Namespace(namespace);
        qTopicPartitionGroup.setTopic(topiC);
        qTopicPartitionGroup.setNamespace(namespacE);
        try {
           return  findByQuery(qTopicPartitionGroup);
        }catch (Exception e){
            logger.error("findByTopic and namespace exception",e);
        }
        return null;
    }
}