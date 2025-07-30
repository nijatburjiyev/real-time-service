package com.companya.realtime.integration;

import com.companya.realtime.model.OutboundEvent;
import com.companya.realtime.repository.OutboundEventRepository;
import com.companya.realtime.service.ResilienceMetricsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class VendorIntegrationService {

    private static final Logger logger = LoggerFactory.getLogger(VendorIntegrationService.class);
    private static final int MAX_RETRY_ATTEMPTS = 5;
    private static final int INITIAL_RETRY_DELAY_MINUTES = 1;

    private final VendorClient vendorClient;
    private final KafkaListenerEndpointRegistry registry;
    private final OutboundEventRepository repository;
    private final ResilienceMetricsService metricsService;
    private final AtomicBoolean consumerPaused = new AtomicBoolean(false);

    public VendorIntegrationService(VendorClient vendorClient,
                                    KafkaListenerEndpointRegistry registry,
                                    OutboundEventRepository repository,
                                    ResilienceMetricsService metricsService) {
        this.vendorClient = vendorClient;
        this.registry = registry;
        this.repository = repository;
        this.metricsService = metricsService;
    }

    /**
     * Persist the payload as a pending outbound event and attempt immediate send.
     * Database-first approach ensures we don't lose data even if vendor call fails.
     */
    @Transactional
    public void processPayload(String payload) {
        logger.debug("Processing outbound payload: {}", payload);

        try {
            OutboundEvent event = repository.save(new OutboundEvent(payload));
            logger.info("Saved outbound event with ID: {} to database", event.getId());

            trySend(event);
        } catch (Exception ex) {
            logger.error("Failed to save outbound event to database: {}", ex.getMessage(), ex);
            throw ex; // Re-throw to ensure caller knows about the database failure
        }
    }

    /**
     * Periodically attempt to send any pending events with exponential backoff.
     */
    @Scheduled(fixedDelay = 30000) // Run every 30 seconds
    public void flushPending() {
        logger.debug("Starting flush of pending outbound events");

        List<OutboundEvent> pendingEvents = repository.findByStatus(OutboundEvent.Status.PENDING);
        logger.info("Found {} pending outbound events to process", pendingEvents.size());

        int successCount = 0;
        int failedCount = 0;

        for (OutboundEvent event : pendingEvents) {
            // Check if we should retry based on exponential backoff
            if (!shouldRetry(event)) {
                logger.debug("Skipping event ID {} - not ready for retry yet", event.getId());
                continue;
            }

            if (trySend(event)) {
                successCount++;
            } else {
                failedCount++;
                // If any event fails, stop processing to avoid overwhelming the vendor
                logger.warn("Stopping batch processing due to vendor failure. Processed: {}, Failed: {}",
                        successCount, failedCount);
                return;
            }
        }

        logger.info("Batch processing completed. Success: {}, Failed: {}", successCount, failedCount);

        // Resume consumer if all pending events are processed successfully
        if (failedCount == 0 && successCount > 0) {
            resumeConsumer();
        }
    }

    private boolean shouldRetry(OutboundEvent event) {
        if (event.getRetryCount() >= MAX_RETRY_ATTEMPTS) {
            logger.warn("Event ID {} has exceeded max retry attempts ({}), marking as failed",
                    event.getId(), MAX_RETRY_ATTEMPTS);
            event.setStatus(OutboundEvent.Status.FAILED);
            repository.save(event);
            return false;
        }

        // Exponential backoff: wait 1, 2, 4, 8, 16 minutes for retries
        long delayMinutes = INITIAL_RETRY_DELAY_MINUTES * (1L << event.getRetryCount());
        LocalDateTime nextRetryTime = event.getLastAttempt().plus(delayMinutes, ChronoUnit.MINUTES);

        boolean shouldRetry = LocalDateTime.now().isAfter(nextRetryTime);
        if (!shouldRetry) {
            logger.debug("Event ID {} not ready for retry. Next retry at: {}", event.getId(), nextRetryTime);
        }

        return shouldRetry;
    }

    @Transactional
    private boolean trySend(OutboundEvent event) {
        logger.debug("Attempting to send event ID: {} (attempt {})", event.getId(), event.getRetryCount() + 1);

        try {
            vendorClient.sendUpdate(event.getPayload());

            event.setStatus(OutboundEvent.Status.SENT);
            event.setLastAttempt(LocalDateTime.now());
            repository.save(event);

            logger.info("Successfully sent event ID: {} to vendor", event.getId());
            return true;

        } catch (Exception ex) {
            logger.warn("Failed to send event ID: {} to vendor (attempt {}): {}",
                    event.getId(), event.getRetryCount() + 1, ex.getMessage());

            // Record metrics for retry and failure
            metricsService.recordRetry();
            metricsService.recordVendorFailure();

            // Update retry count and last attempt time
            event.setRetryCount(event.getRetryCount() + 1);
            event.setLastAttempt(LocalDateTime.now());
            repository.save(event);

            // Pause consumer on vendor failures to prevent overwhelming
            pauseConsumer();
            return false;
        }
    }

    private void pauseConsumer() {
        if (consumerPaused.compareAndSet(false, true)) {
            MessageListenerContainer container = registry.getListenerContainer("changesListener");
            if (container != null && container.isRunning()) {
                container.pause();
                logger.warn("Paused Kafka consumer due to vendor integration failures");
            }
        }
    }

    private void resumeConsumer() {
        if (consumerPaused.compareAndSet(true, false)) {
            MessageListenerContainer container = registry.getListenerContainer("changesListener");
            if (container != null && container.isContainerPaused()) {
                container.resume();
                logger.info("Resumed Kafka consumer - vendor integration is healthy");
            }
        }
    }
}
