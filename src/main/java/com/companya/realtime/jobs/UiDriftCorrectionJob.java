package com.companya.realtime.jobs;

import com.companya.realtime.integration.VendorClient;
import com.companya.realtime.service.RecordService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class UiDriftCorrectionJob {

    private final RecordService recordService;
    private final VendorClient vendorClient;

    public UiDriftCorrectionJob(RecordService recordService, VendorClient vendorClient) {
        this.recordService = recordService;
        this.vendorClient = vendorClient;
    }

    @Scheduled(cron = "0 0 23 * * *") // run every day at 23:00
    public void run() {
        // TODO: fetch UI changes and reconcile with StateDB
        // vendorClient.sendUpdate(payload);
    }
}
