package com.companya.realtime.service;

import com.companya.realtime.model.Record;
import com.companya.realtime.repository.RecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecordService {

    private final RecordRepository recordRepository;
    private static final Logger log = LoggerFactory.getLogger(RecordService.class);

    public RecordService(RecordRepository recordRepository) {
        this.recordRepository = recordRepository;
    }

    @Transactional
    public Record upsert(String externalId, String payload) {
        log.info("Upserting record {}", externalId);
        return recordRepository.findByExternalId(externalId)
                .map(existing -> {
                    existing.setPayload(payload);
                    return existing;
                })
                .orElseGet(() -> recordRepository.save(new Record(externalId, payload)));
    }

    @Transactional
    public void delete(String externalId) {
        recordRepository.findByExternalId(externalId)
                .ifPresent(record -> {
                    log.info("Deleting record {}", externalId);
                    recordRepository.delete(record);
                });
    }
}
