package com.example.realtime.model;

import java.util.Map;

/**
 * Domain representation of a captured change.
 *
 * @param id      unique identifier of the changed entity
 * @param type    operation type such as CREATE or UPDATE
 * @param source  origin of the change (CSV or Kafka)
 * @param payload additional key/value data describing the change
 */
public record ChangeEvent(
        String id,
        ChangeType type,
        EventSource source,
        Map<String, String> payload
) { }
