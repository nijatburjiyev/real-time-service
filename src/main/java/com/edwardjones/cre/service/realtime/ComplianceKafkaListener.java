package com.edwardjones.cre.service.realtime;

import com.edwardjones.cre.model.dto.AdChangeEvent;
import com.edwardjones.cre.model.dto.CrtChangeEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class ComplianceKafkaListener {
    private final ChangeEventProcessor processor;

    @RetryableTopic(attempts = "3", backoff = @Backoff(delay = 1000, multiplier = 2))
    @KafkaListener(topics = "${app.kafka.topics.ad-changes}", groupId = "${spring.kafka.consumer.group-id}")
    public void consumeAdChanges(AdChangeEvent event) {
        log.info("Received AD Change Event for user: {}", event.getPjNumber());
        try {
            processor.processAdChange(event);
        } catch (Exception e) {
            log.error("Fatal error processing AD change for {}: ", event.getPjNumber(), e);
            throw e; // Let Spring Kafka handle retry/DLT
        }
    }

    @RetryableTopic(attempts = "3", backoff = @Backoff(delay = 1000, multiplier = 2))
    @KafkaListener(topics = "${app.kafka.topics.crt-changes}", groupId = "${spring.kafka.consumer.group-id}")
    public void consumeCrtChanges(CrtChangeEvent event) {
        log.info("Received CRT Change Event for team: {}", event.getCrbtId());
        try {
            processor.processCrtChange(event);
        } catch (Exception e) {
            log.error("Fatal error processing CRT change for team {}: ", event.getCrbtId(), e);
            throw e; // Let Spring Kafka handle retry/DLT
        }
    }
}
