package com.companya.realtime.integration;

import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class VendorIntegrationService {

    private final VendorClient vendorClient;
    private final KafkaListenerEndpointRegistry registry;
    private final Queue<String> queue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean consumerPaused = new AtomicBoolean(false);

    public VendorIntegrationService(VendorClient vendorClient, KafkaListenerEndpointRegistry registry) {
        this.vendorClient = vendorClient;
        this.registry = registry;
    }

    public void send(String payload) {
        if (!queue.isEmpty()) {
            queue.add(payload);
            return;
        }
        try {
            vendorClient.sendUpdate(payload);
        } catch (Exception ex) {
            queue.add(payload);
            pauseConsumer();
        }
    }

    @Scheduled(fixedDelay = 30000)
    public void flushQueue() {
        while (!queue.isEmpty()) {
            String payload = queue.peek();
            try {
                vendorClient.sendUpdate(payload);
                queue.remove();
            } catch (Exception ex) {
                pauseConsumer();
                return;
            }
        }
        resumeConsumer();
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
