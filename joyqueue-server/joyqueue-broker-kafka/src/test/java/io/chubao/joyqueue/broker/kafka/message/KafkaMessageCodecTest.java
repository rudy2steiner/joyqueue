package io.chubao.joyqueue.broker.kafka.message;

import org.apache.kafka.common.record.*;
import org.junit.Assert;
import org.junit.Test;
import java.nio.ByteBuffer;
import java.util.List;

public class KafkaMessageCodecTest {


    @Test
    public void messageV0WithCompressionTest(){
        ByteBuffer buffer= ByteBuffer.allocate(1024);
        SimpleRecord[] simpleRecords = new SimpleRecord[] {
                new SimpleRecord("aaaa".getBytes(), "1".getBytes()),
                new SimpleRecord("bbbb".getBytes(), "2".getBytes()),
                new SimpleRecord("cccc".getBytes(), "3".getBytes())
        };
        MemoryRecordsBuilder builder=MemoryRecords.builder(buffer, RecordBatch.MAGIC_VALUE_V0, CompressionType.GZIP, TimestampType.CREATE_TIME, 0L);
        for(SimpleRecord r:simpleRecords){
            builder.append(r);
        }
        ByteBuffer batchMessageBuffer=builder.build().buffer();
        try {
            List<KafkaBrokerMessage> kafkaBrokerMessages=KafkaMessageSerializer.readMessages(batchMessageBuffer);
            Assert.assertEquals(simpleRecords.length,kafkaBrokerMessages.size());
            Assert.assertEquals(simpleRecords.length,kafkaBrokerMessages.size());
            Assert.assertTrue(checkNoneBatchMessagesIdentity(simpleRecords,kafkaBrokerMessages));
        }catch (Exception e){
            Assert.fail(e.getMessage());
        }
    }

    @Test
    public void messageV0WithoutCompressionTest(){
        ByteBuffer buffer= ByteBuffer.allocate(1024);
        SimpleRecord[] simpleRecords = new SimpleRecord[] {
                new SimpleRecord("aaaa".getBytes(), "1".getBytes()),
                new SimpleRecord("bbbb".getBytes(), "2".getBytes()),
                new SimpleRecord("cccc".getBytes(), "3".getBytes())
        };
        MemoryRecordsBuilder builder=MemoryRecords.builder(buffer, RecordBatch.MAGIC_VALUE_V0, CompressionType.NONE, TimestampType.CREATE_TIME, 0L);
        for(SimpleRecord r:simpleRecords){
            builder.append(r);
        }
        ByteBuffer batchMessageBuffer=builder.build().buffer();
        try {
            List<KafkaBrokerMessage> kafkaBrokerMessages=KafkaMessageSerializer.readMessages(batchMessageBuffer);
            Assert.assertEquals(simpleRecords.length,kafkaBrokerMessages.size());
            Assert.assertTrue(checkNoneBatchMessagesIdentity(simpleRecords,kafkaBrokerMessages));
        }catch (Exception e){
            Assert.fail(e.getMessage());
        }
    }

    /**
     *  Check none batch messages
     **/
    public boolean checkNoneBatchMessagesIdentity(SimpleRecord[] records,List<KafkaBrokerMessage> messages){
        for(int i=0;i<records.length;i++){
            KafkaBrokerMessage message=messages.get(i);
            SimpleRecord received=new SimpleRecord(message.getTimestamp(),message.getKey(),message.getValue());
            if(!received.equals(records[i])){
                return false;
            }
            if(message.getOffset()!=i){
                return false;
            }
        }
        return true;
    }
}

