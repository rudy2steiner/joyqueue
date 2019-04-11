package com.jd.journalq.handler.routing.command.monitor;


import com.jd.journalq.handler.error.ErrorCode;
import com.jd.journalq.model.domain.PartitionOffset;
import com.jd.journalq.model.domain.ResetOffsetInfo;
import com.jd.journalq.model.domain.Subscribe;
import com.jd.journalq.monitor.PartitionLeaderAckMonitorInfo;
import com.jd.journalq.service.ConsumeOffsetService;
import com.jd.journalq.util.NullUtil;
import com.jd.laf.binding.annotation.Value;
import com.jd.laf.web.vertx.Command;
import com.jd.laf.web.vertx.annotation.Body;
import com.jd.laf.web.vertx.annotation.Path;
import com.jd.laf.web.vertx.annotation.QueryParam;
import com.jd.laf.web.vertx.pool.Poolable;
import com.jd.laf.web.vertx.response.Response;
import com.jd.laf.web.vertx.response.Responses;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class ConsumeOffsetCommand implements Command<Response>, Poolable {

    private final static Logger logger = LoggerFactory.getLogger(ConsumeOffsetCommand.class);

    @Value(nullable = false)
    private ConsumeOffsetService consumeOffsetService;

    @Override
    public Response execute() throws Exception {
        throw  new UnsupportedOperationException("unsupported");
    }

    @Path("offsets")
    public Response offsets(@Body Subscribe subscribe){
        try {
            return Responses.success(consumeOffsetService.offsets(subscribe));
        } catch (Exception e) {
            logger.error("query consumer offset info error.", e);
            return Responses.error(ErrorCode.NoTipError.getCode(), ErrorCode.NoTipError.getStatus(), e.getMessage());
        }
    }

    /**
     *
     *
     */
    @Path("resetBound")
    public Response offsetBound(@Body Subscribe subscribe, @QueryParam("location") String location){
        PartitionOffset.Location loc=PartitionOffset.Location.valueOf(location);
        List<PartitionOffset> partitionOffsets=new ArrayList<>();
        List<PartitionLeaderAckMonitorInfo>  partitionAckMonitorInfos=consumeOffsetService.offsets(subscribe);
        PartitionOffset partitionOffset;
        for(PartitionLeaderAckMonitorInfo p:partitionAckMonitorInfos){
            if(p.isLeader()) {
                partitionOffset = new PartitionOffset();
                partitionOffset.setPartition(p.getPartition());
                if (loc == PartitionOffset.Location.MAX) {
                    partitionOffset.setOffset(p.getRightIndex());
                } else partitionOffset.setOffset(p.getLeftIndex());
                partitionOffsets.add(partitionOffset);
            }
        }
        boolean result=consumeOffsetService.resetOffset(subscribe, partitionOffsets);
        return result?Responses.success("success"):Responses.error(ErrorCode.ServiceError.getCode(),"reset failed");
    }

    /**
     *
     *
     */
    @Path("resetByTime")
    public Response resetByTime(@Body Subscribe subscribe,@QueryParam("timestamp") String timestamp){
        try {
            Long time = Long.valueOf(timestamp);
            boolean result = consumeOffsetService.resetOffset(subscribe, time);
            return result ? Responses.success("success") : Responses.error(ErrorCode.ServiceError.getCode(), "reset failed");
        } catch (Exception e) {
            logger.error("query consumer offset info error.", e);
            return Responses.error(ErrorCode.NoTipError.getCode(), ErrorCode.NoTipError.getStatus(), e.getMessage());
        }
    }

    /**
     * Reset partition offset for @code subscribe
     *
     */
    @Path("resetPartition")
    public Response resetPartition(@Body Subscribe subscribe,@QueryParam("partition") String partition,@QueryParam("offset") String offset){
        try {
            if (NullUtil.isEmpty(partition) || NullUtil.isEmpty(offset)) {
                return Responses.error(ErrorCode.BadRequest.getCode(), "partition and offset can't be null");
            }
            boolean result = consumeOffsetService.resetOffset(subscribe, Short.valueOf(partition), Long.valueOf(offset));
            return result ? Responses.success("success") : Responses.error(ErrorCode.ServiceError.getCode(), "reset failed");
        } catch (Exception e) {
            logger.error("query consumer offset info error.", e);
            return Responses.error(ErrorCode.NoTipError.getCode(), ErrorCode.NoTipError.getStatus(), e.getMessage());
        }

    }


    /**
     *
     * Reset  offsets for @code subscribe
     *
     */
    @Path("reset")
    public Response resetOffsets(@Body ResetOffsetInfo offsetInfo){
        try {
            boolean result = consumeOffsetService.resetOffset(offsetInfo.getSubscribe(), offsetInfo.getPartitionOffsets());
            return result ? Responses.success("success") : Responses.error(ErrorCode.ServiceError.getCode(), "reset failed");
        } catch (Exception e) {
            logger.error("query consumer offset info error.", e);
            return Responses.error(ErrorCode.NoTipError.getCode(), ErrorCode.NoTipError.getStatus(), e.getMessage());
        }
    }


    @Override
    public void clean() {

    }
}