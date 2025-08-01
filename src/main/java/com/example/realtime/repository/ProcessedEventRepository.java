package com.example.realtime.repository;

import com.example.realtime.model.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, Long> {
    boolean existsByEventKey(String eventKey);
}
