package com.companya.realtime.repository;

import com.companya.realtime.model.Record;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RecordRepository extends JpaRepository<Record, Long> {
    Optional<Record> findByExternalId(String externalId);
}
