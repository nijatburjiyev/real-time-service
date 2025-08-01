package com.companya.realtime.integration;

import com.companya.realtime.integration.exception.PermanentException;
import com.companya.realtime.integration.exception.RetryableException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class VendorIntegrationService {

    private final VendorClient vendorClient;
    private final KafkaListenerEndpointRegistry registry;
    private final Counter sentCounter;
    private final Counter failedCounter;
    private final AtomicBoolean consumerPaused = new AtomicBoolean(false);
    private static final Logger log = LoggerFactory.getLogger(VendorIntegrationService.class);

    public VendorIntegrationService(VendorClient vendorClient,
                                    KafkaListenerEndpointRegistry registry,
                                    MeterRegistry meterRegistry) {
        this.vendorClient = vendorClient;
        this.registry = registry;
        this.sentCounter = meterRegistry.counter("vendor.events.sent");
        this.failedCounter = meterRegistry.counter("vendor.events.failed");
        vendorClient.getCircuitBreaker().getEventPublisher()
                .onStateTransition(event -> {
                    switch (event.getStateTransition()) {
                        case CLOSED_TO_OPEN -> pauseConsumer();
                        case OPEN_TO_HALF_OPEN, HALF_OPEN_TO_CLOSED -> resumeConsumer();
                    }
                });
    }

    /**
     * Send the payload to the vendor service and track metrics. The call
     * is guarded by a circuit breaker so repeated failures will pause the
     * Kafka consumer until the vendor becomes healthy again.
     */
    public void processPayload(String payload) {
        try {
            vendorClient.sendUpdate(payload);
            sentCounter.increment();
            log.info("Sent payload to vendor");
        } catch (PermanentException | RetryableException ex) {
            failedCounter.increment();
            throw ex;
        }
    }

    private void pauseConsumer() {
        if (consumerPaused.get()) {
            return;
        }
        MessageListenerContainer container = registry.getListenerContainer("changesListener");
        if (container != null) {
            container.pause();
            consumerPaused.set(true);
            log.info("Kafka consumer paused");
        }
    }

    private void resumeConsumer() {
        if (!consumerPaused.get()) {
            return;
        }
        MessageListenerContainer container = registry.getListenerContainer("changesListener");
        if (container != null) {
            container.resume();
            consumerPaused.set(false);
            log.info("Kafka consumer resumed");
        }
    }
}
