package com.example.realtime.repository;

import com.example.realtime.model.FailedEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FailedEventRepository extends JpaRepository<FailedEvent, Long> {
}
