package com.example.realtime.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/**
 * Sample JPA entity storing routing decisions per identifier.
 */
@Entity
public class RoutingRule {

    @Id
    private String id; // identifier to match against incoming events

    private String route; // vendor route selected by business rules

    public RoutingRule() {
    }

    public RoutingRule(String id, String route) {
        this.id = id;
        this.route = route;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getRoute() {
        return route;
    }

    public void setRoute(String route) {
        this.route = route;
    }
}
