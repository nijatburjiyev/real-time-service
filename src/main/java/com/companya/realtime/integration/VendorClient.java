package com.companya.realtime.integration;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class VendorClient {

    private final RestTemplate restTemplate = new RestTemplate();

    public void sendUpdate(String payload) {
        // TODO: implement call to vendor REST API
        // restTemplate.postForEntity("https://vendor.example/api", payload, Void.class);
    }
}
