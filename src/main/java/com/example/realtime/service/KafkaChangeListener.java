package com.example.realtime.service;

import com.example.realtime.model.ChangeEvent;
import com.example.realtime.model.ChangeType;
import com.example.realtime.model.EventSource;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Listens for change events directly from Kafka and routes them immediately.
 */
@Component
public class KafkaChangeListener {

    private static final Logger log = LoggerFactory.getLogger(KafkaChangeListener.class);
    private final RoutingService routingService; // pushes events through business rules

    public KafkaChangeListener(RoutingService routingService) {
        this.routingService = routingService;
    }

    @KafkaListener(topics = "${kafka.topic:changes}", groupId = "real-time-service")
    public void onMessage(ConsumerRecord<String, String> record) {
        log.info("Received message from Kafka: {}", record.value());
        // Messages are expected in the form: id|type|value
        String[] parts = record.value().split("\\|");
        if (parts.length < 2) {
            return; // ignore malformed messages
        }
        String id = parts[0];
        ChangeType type = ChangeType.valueOf(parts[1]);
        Map<String, String> payload = Map.of("value", parts.length > 2 ? parts[2] : "");
        ChangeEvent event = new ChangeEvent(id, type, EventSource.KAFKA, payload);
        routingService.route(event); // immediately forward to routing layer
    }
}
