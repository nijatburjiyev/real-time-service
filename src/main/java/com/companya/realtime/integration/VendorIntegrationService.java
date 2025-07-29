package com.companya.realtime.integration;

import com.companya.realtime.model.OutboundEvent;
import com.companya.realtime.repository.OutboundEventRepository;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class VendorIntegrationService {

    private final VendorClient vendorClient;
    private final KafkaListenerEndpointRegistry registry;
    private final OutboundEventRepository repository;
    private final AtomicBoolean consumerPaused = new AtomicBoolean(false);

    public VendorIntegrationService(VendorClient vendorClient,
                                    KafkaListenerEndpointRegistry registry,
                                    OutboundEventRepository repository) {
        this.vendorClient = vendorClient;
        this.registry = registry;
        this.repository = repository;
    }

    /**
     * Persist the payload as a pending outbound event and attempt immediate send.
     */
    public void processPayload(String payload) {
        OutboundEvent event = repository.save(new OutboundEvent(payload));
        trySend(event);
    }

    /**
     * Periodically attempt to send any pending events.
     */
    @Scheduled(fixedDelay = 30000)
    public void flushPending() {
        for (OutboundEvent event : repository.findByStatus(OutboundEvent.Status.PENDING)) {
            if (!trySend(event)) {
                return;
            }
        }
        resumeConsumer();
    }

    private boolean trySend(OutboundEvent event) {
        try {
            vendorClient.sendUpdate(event.getPayload());
            event.setStatus(OutboundEvent.Status.SENT);
            repository.save(event);
            return true;
        } catch (Exception ex) {
            pauseConsumer();
            return false;
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
        }
    }
}
