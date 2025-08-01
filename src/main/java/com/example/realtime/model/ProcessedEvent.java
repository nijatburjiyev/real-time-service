package com.example.realtime.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "processed_events", uniqueConstraints = @UniqueConstraint(columnNames = "event_key"))
public class ProcessedEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_key", nullable = false, unique = true)
    private String eventKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected ProcessedEvent() {
    }

    public ProcessedEvent(String eventKey) {
        this.eventKey = eventKey;
    }

    public Long getId() {
        return id;
    }

    public String getEventKey() {
        return eventKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
