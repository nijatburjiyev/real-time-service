package com.companya.realtime.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
public class OutboundEvent {

    public enum Status {
        PENDING, SENT, FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 2048)
    private String payload;

    @Enumerated(EnumType.STRING)
    private Status status = Status.PENDING;

    @Column(name = "retry_count")
    private int retryCount = 0;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "last_attempt")
    private LocalDateTime lastAttempt = LocalDateTime.now();

    public OutboundEvent() {
    }

    public OutboundEvent(String payload) {
        this.payload = payload;
        this.status = Status.PENDING;
        this.retryCount = 0;
        this.createdAt = LocalDateTime.now();
        this.lastAttempt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getLastAttempt() {
        return lastAttempt;
    }

    public void setLastAttempt(LocalDateTime lastAttempt) {
        this.lastAttempt = lastAttempt;
    }
}
