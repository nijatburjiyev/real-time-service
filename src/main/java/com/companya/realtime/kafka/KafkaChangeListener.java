package com.companya.realtime.kafka;

import com.companya.realtime.integration.VendorIntegrationService;
import com.companya.realtime.service.RecordService;
import com.companya.realtime.service.ResilienceMetricsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public class KafkaChangeListener {

    private static final Logger logger = LoggerFactory.getLogger(KafkaChangeListener.class);

    private final RecordService recordService;
    private final VendorIntegrationService vendorService;
    private final ResilienceMetricsService metricsService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public KafkaChangeListener(RecordService recordService, VendorIntegrationService vendorService,
                              ResilienceMetricsService metricsService) {
        this.recordService = recordService;
        this.vendorService = vendorService;
        this.metricsService = metricsService;
    }

    @KafkaListener(id = "changesListener", topics = "changes", groupId = "realtime-service", containerFactory = "manualAckContainerFactory")
    public void consume(ConsumerRecord<String, String> record, Acknowledgment ack) {
        logger.info("Received Kafka message - Topic: {}, Partition: {}, Offset: {}, Key: {}",
                   record.topic(), record.partition(), record.offset(), record.key());

        try {
            String key = record.key();
            String payload = record.value();

            // Validate basic message structure
            if (key == null || key.trim().isEmpty()) {
                logger.warn("Skipping message with null or empty key at offset {}", record.offset());
                metricsService.recordPoisonMessage();
                ack.acknowledge();
                return;
            }

            if (payload == null || payload.trim().isEmpty()) {
                logger.warn("Skipping message with null or empty payload for key {} at offset {}", key, record.offset());
                metricsService.recordPoisonMessage();
                ack.acknowledge();
                return;
            }

            ObjectType objectType = ObjectType.fromHeader(getHeader(record, "objectType"));
            EventAction action = EventAction.fromHeader(getHeader(record, "action"));
            EventType eventType = EventType.from(objectType, action);

            logger.debug("Processing event - Key: {}, ObjectType: {}, Action: {}, EventType: {}",
                        key, objectType, action, eventType);

            JsonNode jsonPayload;
            try {
                jsonPayload = objectMapper.readTree(payload);
                logger.debug("Successfully parsed JSON payload for key: {}", key);
            } catch (Exception ex) {
                logger.error("Invalid JSON payload for key {} at offset {}, skipping poison message: {}",
                           key, record.offset(), ex.getMessage());
                metricsService.recordPoisonMessage();
                // Acknowledge poison message to skip it
                ack.acknowledge();
                return;
            }

            KafkaEvent event = new KafkaEvent(key, eventType, jsonPayload);

            // Database-first approach: Always persist to database first
            boolean databaseSuccess = handleEvent(event);

            if (databaseSuccess) {
                // Only attempt vendor integration after successful database operation
                try {
                    vendorService.processPayload(payload);
                    logger.info("Successfully processed event for key: {}", key);
                } catch (Exception ex) {
                    // Vendor failure doesn't prevent acknowledgment since database is updated
                    logger.warn("Vendor integration failed for key {}, but database is updated. Error: {}",
                              key, ex.getMessage());
                }

                // Always acknowledge after successful database operation
                ack.acknowledge();
                logger.debug("Message acknowledged for key: {}", key);
            } else {
                // Don't acknowledge if database operation failed - let Kafka retry
                logger.error("Database operation failed for key {}, message not acknowledged for retry", key);
            }

        } catch (Exception ex) {
            logger.error("Unexpected error processing message at offset {}: {}", record.offset(), ex.getMessage(), ex);
            // Don't acknowledge on unexpected errors to trigger retry
        }
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
            logger.warn("Event type is null for key: {}, skipping processing", event.key());
            return true; // Acknowledge since this is not a retryable error
        }

        try {
            switch (event.eventType()) {
                case TEAMCREATE, TEAMUPDATE, MEMBERCREATE, MEMBERUPDATE -> {
                    logger.info("Upserting record for key: {} with event type: {}", event.key(), event.eventType());
                    recordService.upsert(event.key(), event.payload().toString());
                    logger.debug("Successfully upserted record for key: {}", event.key());
                }
                case TEAMEND, MEMBEREND -> {
                    logger.info("Deleting record for key: {} with event type: {}", event.key(), event.eventType());
                    recordService.delete(event.key());
                    logger.debug("Successfully deleted record for key: {}", event.key());
                }
            }
            return true;
        } catch (Exception ex) {
            logger.error("Database operation failed for key: {} with event type: {}: {}",
                        event.key(), event.eventType(), ex.getMessage(), ex);
            metricsService.recordDatabaseFailure();
            return false;
        }
    }
}
