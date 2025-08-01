package com.companya.realtime.kafka;

import com.companya.realtime.integration.VendorClient;
import com.companya.realtime.service.RecordService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class KafkaChangeListener {

    private final RecordService recordService;
    private final VendorClient vendorClient;

    public KafkaChangeListener(RecordService recordService, VendorClient vendorClient) {
        this.recordService = recordService;
        this.vendorClient = vendorClient;
    }

    @KafkaListener(topics = "changes", groupId = "realtime-service")
    public void consume(ConsumerRecord<String, String> record) {
        String key = record.key();
        String payload = record.value();

        recordService.upsert(key, payload);
        vendorClient.sendUpdate(payload);
    }
}
