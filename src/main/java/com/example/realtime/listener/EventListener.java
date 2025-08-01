package com.example.realtime.listener;

import com.example.realtime.model.FailedEvent;
import com.example.realtime.model.ProcessedEvent;
import com.example.realtime.repository.FailedEventRepository;
import com.example.realtime.repository.ProcessedEventRepository;
import com.example.realtime.service.VendorClient;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;

@Component
public class EventListener {

    private final ProcessedEventRepository processedRepo;
    private final FailedEventRepository failedRepo;
    private final VendorClient vendorClient;

    public EventListener(ProcessedEventRepository processedRepo,
                         FailedEventRepository failedRepo,
                         VendorClient vendorClient) {
        this.processedRepo = processedRepo;
        this.failedRepo = failedRepo;
        this.vendorClient = vendorClient;
    }

    @KafkaListener(topics = "${app.kafka.topic}", containerFactory = "kafkaListenerContainerFactory")
    public void handleEvent(ConsumerRecord<String, String> record, Acknowledgment ack,
                            @Header(KafkaHeaders.RECEIVED_KEY) String key) {
        try {
            if (processedRepo.existsByEventKey(key)) {
                ack.acknowledge();
                return;
            }
            vendorClient.send(record.value());
            processedRepo.save(new ProcessedEvent(key));
        } catch (Exception e) {
            failedRepo.save(new FailedEvent(key, record.value(), e.getMessage()));
        } finally {
            ack.acknowledge();
        }
    }
}
