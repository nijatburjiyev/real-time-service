package com.companya.realtime.kafka;

import com.companya.realtime.integration.VendorIntegrationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.companya.realtime.integration.exception.PermanentException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public class KafkaChangeListener {

    private final VendorIntegrationService vendorService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(KafkaChangeListener.class);

    public KafkaChangeListener(VendorIntegrationService vendorService, KafkaTemplate<String, String> kafkaTemplate) {
        this.vendorService = vendorService;
        this.kafkaTemplate = kafkaTemplate;
    }

    // Manual acknowledgments ensure we only commit the offset after both the
    // database update and vendor dispatch logic have run.
    @KafkaListener(id = "changesListener", topics = "changes", groupId = "realtime-service", containerFactory = "manualAckContainerFactory")
    public void consume(ConsumerRecord<String, String> record, Acknowledgment ack) {
        String key = record.key();
        String payload = record.value();

        if (key == null || payload == null) {
            log.warn("Missing key or payload, skipping");
            ack.acknowledge();
            return;
        }

        ObjectType objectType = ObjectType.fromHeader(getHeader(record, "objectType"));
        EventAction action = EventAction.fromHeader(getHeader(record, "action"));
        EventType eventType = EventType.from(objectType, action);

        log.debug("Received message key={} objectType={} action={}", key, objectType, action);

        JsonNode jsonPayload;
        try {
            jsonPayload = objectMapper.readTree(payload);
        } catch (Exception ex) {
            log.warn("Malformed JSON for key {}: {}", key, ex.getMessage());
            publishToDlt(record, ex.getMessage());
            ack.acknowledge();
            return;
        }

        KafkaEvent event = new KafkaEvent(key, eventType, jsonPayload);
        try {
            if (!handleEvent(event)) {
                ack.acknowledge();
                return;
            }
            ack.acknowledge();
        } catch (PermanentException pe) {
            log.error("Permanent failure for key {}: {}", key, pe.getMessage());
            publishToDlt(record, pe.getMessage());
            ack.acknowledge();
        }
    }

    private void publishToDlt(ConsumerRecord<String, String> record, String error) {
        ProducerRecord<String, String> dlt = new ProducerRecord<>(record.topic() + ".DLT", record.key(), record.value());
        dlt.headers().add("error", error.getBytes());
        kafkaTemplate.send(dlt);
    }

    private String getHeader(ConsumerRecord<String, String> record, String key) {
        Header header = record.headers().lastHeader(key);
        if (header == null) {
            return null;
        }
        return new String(header.value());
    }

    private boolean handleEvent(KafkaEvent event) {
        if (event.eventType() == null) {
            log.warn("Unknown event type for key {}", event.key());
            return false;
        }

        switch (event.eventType()) {
            case TEAMCREATE, TEAMUPDATE, MEMBERCREATE, MEMBERUPDATE,
                    TEAMEND, MEMBEREND -> vendorService.processPayload(event.payload().toString());
        }
        return true;
    }
}
