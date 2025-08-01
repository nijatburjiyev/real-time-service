package com.example.realtime.service;

import com.example.realtime.model.FailedEvent;
import com.example.realtime.model.ProcessedEvent;
import com.example.realtime.repository.FailedEventRepository;
import com.example.realtime.repository.ProcessedEventRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class FailedEventProcessor {

    private final FailedEventRepository failedRepo;
    private final ProcessedEventRepository processedRepo;
    private final VendorClient vendorClient;

    @Value("${app.vendor.paused:false}")
    private boolean vendorPaused;

    public FailedEventProcessor(FailedEventRepository failedRepo,
                                ProcessedEventRepository processedRepo,
                                VendorClient vendorClient) {
        this.failedRepo = failedRepo;
        this.processedRepo = processedRepo;
        this.vendorClient = vendorClient;
    }

    @Scheduled(fixedDelayString = "${app.retry.fixed-delay:30000}")
    public void retryFailedEvents() {
        if (vendorPaused) {
            return;
        }
        for (FailedEvent event : failedRepo.findAll()) {
            try {
                if (processedRepo.existsByEventKey(event.getEventKey())) {
                    failedRepo.delete(event);
                    continue;
                }
                vendorClient.send(event.getPayload());
                processedRepo.save(new ProcessedEvent(event.getEventKey()));
                failedRepo.delete(event);
            } catch (Exception ignored) {
                // leave event for next run
            }
        }
    }
}
