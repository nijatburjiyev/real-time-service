package com.companya.realtime.kafka;

public enum ObjectType {
    TEAM,
    MEMBER;

    public static ObjectType fromHeader(String header) {
        if (header == null) {
            return null;
        }
        try {
            return ObjectType.valueOf(header.toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
