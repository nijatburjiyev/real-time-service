package com.companya.realtime.kafka;

import com.fasterxml.jackson.databind.JsonNode;

public record KafkaEvent(
        String key,
        EventType eventType,
        JsonNode payload) {
}
