package com.companya.realtime.kafka;

import com.companya.realtime.integration.VendorClient;
import com.companya.realtime.service.RecordService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class KafkaChangeListener {

    private final RecordService recordService;
    private final VendorClient vendorClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public KafkaChangeListener(RecordService recordService, VendorClient vendorClient) {
        this.recordService = recordService;
        this.vendorClient = vendorClient;
    }

    @KafkaListener(topics = "changes", groupId = "realtime-service")
    public void consume(ConsumerRecord<String, String> record) {
        String key = record.key();
        String payload = record.value();

        ObjectType objectType = ObjectType.fromHeader(getHeader(record, "objectType"));
        EventAction action = EventAction.fromHeader(getHeader(record, "action"));
        EventType eventType = EventType.from(objectType, action);

        JsonNode jsonPayload;
        try {
            jsonPayload = objectMapper.readTree(payload);
        } catch (Exception ex) {
            // invalid payload, skip
            return;
        }

        KafkaEvent event = new KafkaEvent(key, eventType, jsonPayload);
        handleEvent(event);
    }

    private String getHeader(ConsumerRecord<String, String> record, String key) {
        Header header = record.headers().lastHeader(key);
        if (header == null) {
            return null;
        }
        return new String(header.value());
    }

    private void handleEvent(KafkaEvent event) {
        if (event.eventType() == null) {
            return;
        }

        switch (event.eventType()) {
            case TEAMCREATE, TEAMUPDATE, MEMBERCREATE, MEMBERUPDATE -> {
                recordService.upsert(event.key(), event.payload().toString());
                vendorClient.sendUpdate(event.payload().toString());
            }
            case TEAMEND, MEMBEREND -> {
                recordService.delete(event.key());
                vendorClient.sendUpdate(event.payload().toString());
            }
        }
    }
}
