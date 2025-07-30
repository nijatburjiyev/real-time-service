package com.companya.realtime.repository;

import com.companya.realtime.model.OutboundEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface OutboundEventRepository extends JpaRepository<OutboundEvent, Long> {
    List<OutboundEvent> findByStatus(OutboundEvent.Status status);

    int countByStatus(OutboundEvent.Status status);

    List<OutboundEvent> findByStatusAndLastAttemptBefore(OutboundEvent.Status status, LocalDateTime before);

    List<OutboundEvent> findByStatusAndLastAttemptAfter(OutboundEvent.Status status, LocalDateTime after);
}
