package com.example.realtime.service;

import com.example.realtime.model.ChangeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Sends processed change events to the vendor via REST API.
 */
@Component
public class VendorClient {

    private static final Logger log = LoggerFactory.getLogger(VendorClient.class);

    private final RestTemplate restTemplate = new RestTemplate(); // simple HTTP client
    private final String vendorUrl; // base URL of the vendor API

    public VendorClient(@Value("${vendor.api.url}") String vendorUrl) {
        this.vendorUrl = vendorUrl;
    }

    public void send(ChangeEvent event, String route) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON); // send JSON payload
        HttpEntity<ChangeEvent> request = new HttpEntity<>(event, headers);
        log.info("Sending event {} to vendor route {}", event.id(), route);
        // Fire-and-forget POST; a real client would handle errors and responses
        restTemplate.postForLocation(vendorUrl + "/" + route, request);
    }
}
