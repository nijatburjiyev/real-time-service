package com.companya.realtime.service;

import com.companya.realtime.model.Record;
import com.companya.realtime.repository.RecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecordService {

    private static final Logger logger = LoggerFactory.getLogger(RecordService.class);

    private final RecordRepository recordRepository;

    public RecordService(RecordRepository recordRepository) {
        this.recordRepository = recordRepository;
    }

    @Transactional
    public Record upsert(String externalId, String payload) {
        logger.debug("Upserting record with externalId: {}", externalId);

        try {
            Record result = recordRepository.findByExternalId(externalId)
                    .map(existing -> {
                        logger.debug("Updating existing record with externalId: {}", externalId);
                        existing.setPayload(payload);
                        return recordRepository.save(existing);
                    })
                    .orElseGet(() -> {
                        logger.debug("Creating new record with externalId: {}", externalId);
                        return recordRepository.save(new Record(externalId, payload));
                    });

            logger.info("Successfully upserted record with externalId: {}, ID: {}", externalId, result.getId());
            return result;
        } catch (Exception ex) {
            logger.error("Failed to upsert record with externalId: {}: {}", externalId, ex.getMessage(), ex);
            throw ex;
        }
    }

    @Transactional
    public void delete(String externalId) {
        logger.debug("Deleting record with externalId: {}", externalId);

        try {
            recordRepository.findByExternalId(externalId)
                    .ifPresentOrElse(
                            record -> {
                                recordRepository.delete(record);
                                logger.info("Successfully deleted record with externalId: {}, ID: {}", externalId, record.getId());
                            },
                            () -> logger.warn("Record with externalId: {} not found for deletion", externalId)
                    );
        } catch (Exception ex) {
            logger.error("Failed to delete record with externalId: {}: {}", externalId, ex.getMessage(), ex);
            throw ex;
        }
    }
}
