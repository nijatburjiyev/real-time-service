package com.example.realtime.service;

import com.example.realtime.model.ChangeEvent;
import com.example.realtime.model.RoutingRule;
import com.example.realtime.repository.RoutingRuleRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Applies business rules to route events to the appropriate vendor endpoint.
 */
@Service
public class RoutingService {

    private final RoutingRuleRepository repository; // stores routing decisions
    private final VendorClient vendorClient; // client to call vendor API

    public RoutingService(RoutingRuleRepository repository, VendorClient vendorClient) {
        this.repository = repository;
        this.vendorClient = vendorClient;
    }

    public void route(ChangeEvent event) {
        Optional<RoutingRule> rule = repository.findById(event.id()); // lookup rule for id
        String route = rule.map(RoutingRule::getRoute).orElse("default"); // default when missing
        vendorClient.send(event, route); // send event to chosen route
    }
}
