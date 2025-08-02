package com.example.realtime.service;

import com.example.realtime.model.ChangeEvent;
import com.example.realtime.model.ChangeType;
import com.example.realtime.model.EventSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Periodically reads a CSV file and converts rows into change events.
 */
@Component
public class CsvChangeScheduler {

    private static final Logger log = LoggerFactory.getLogger(CsvChangeScheduler.class);

    private final Path csvPath; // location of the CSV file
    private final RoutingService routingService; // routes parsed events

    public CsvChangeScheduler(@Value("${csv.file.path:changes.csv}") String csvFile,
                              RoutingService routingService) {
        this.csvPath = Path.of(csvFile); // resolve file path from configuration
        this.routingService = routingService;
    }

    @Scheduled(cron = "${csv.schedule:0 0 2 * * *}")
    public void readCsv() throws IOException {
        // Skip execution if the file isn't present
        if (!Files.exists(csvPath)) {
            log.warn("CSV file {} not found", csvPath);
            return;
        }
        Files.lines(csvPath).forEach(line -> {
            // Very naive parsing; a real implementation would handle headers and escaping
            String[] parts = line.split(",");
            if (parts.length < 2) {
                return; // ignore malformed rows
            }
            String id = parts[0];
            ChangeType type = ChangeType.valueOf(parts[1]);
            Map<String, String> payload = new HashMap<>();
            if (parts.length > 2) {
                payload.put("value", parts[2]);
            }
            ChangeEvent event = new ChangeEvent(id, type, EventSource.CSV, payload);
            routingService.route(event); // delegate to business routing layer
        });
    }
}
