package com.companya.realtime.repository;

import com.companya.realtime.model.OutboundEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OutboundEventRepository extends JpaRepository<OutboundEvent, Long> {
    List<OutboundEvent> findByStatus(OutboundEvent.Status status);
}
