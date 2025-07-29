package com.companya.realtime.model;

import jakarta.persistence.*;

@Entity
public class OutboundEvent {

    public enum Status {
        PENDING, SENT
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 2048)
    private String payload;

    @Enumerated(EnumType.STRING)
    private Status status = Status.PENDING;

    public OutboundEvent() {
    }

    public OutboundEvent(String payload) {
        this.payload = payload;
        this.status = Status.PENDING;
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
}
