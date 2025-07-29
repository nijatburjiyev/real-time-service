package com.companya.realtime.kafka;

public enum EventAction {
    CREATE,
    UPDATE,
    END;

    public static EventAction fromHeader(String header) {
        if (header == null) {
            return null;
        }
        try {
            return EventAction.valueOf(header.toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
