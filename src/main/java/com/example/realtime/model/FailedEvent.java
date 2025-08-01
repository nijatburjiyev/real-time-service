package com.example.realtime.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "failed_events")
public class FailedEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_key", nullable = false)
    private String eventKey;

    @Lob
    @Column(nullable = false)
    private String payload;

    @Column
    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected FailedEvent() {
    }

    public FailedEvent(String eventKey, String payload, String reason) {
        this.eventKey = eventKey;
        this.payload = payload;
        this.reason = reason;
    }

    public Long getId() {
        return id;
    }

    public String getEventKey() {
        return eventKey;
    }

    public String getPayload() {
        return payload;
    }

    public String getReason() {
        return reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
