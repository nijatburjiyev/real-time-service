package com.companya.realtime.jobs;

import com.companya.realtime.integration.VendorClient;
import com.companya.realtime.service.RecordService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class DailyCsvSyncJob {

    private final RecordService recordService;
    private final VendorClient vendorClient;
    private static final Logger log = LoggerFactory.getLogger(DailyCsvSyncJob.class);

    public DailyCsvSyncJob(RecordService recordService, VendorClient vendorClient) {
        this.recordService = recordService;
        this.vendorClient = vendorClient;
    }

    @Scheduled(cron = "0 0 18 * * *") // run every day at 18:00
    public void run() {
        log.info("Running daily CSV sync job");
        // TODO: read CSV file and upsert records
        // Example: recordService.upsert(id, payload);
        // vendorClient.sendUpdate(payload);
        log.info("Daily CSV sync job completed");
    }
}
