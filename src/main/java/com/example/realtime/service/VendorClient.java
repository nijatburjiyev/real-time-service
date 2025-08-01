package com.example.realtime.service;

import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class VendorClient {

    private final RestTemplate restTemplate;

    public VendorClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @RateLimiter(name = "vendor")
    @Retry(name = "vendor")
    @CircuitBreaker(name = "vendor")
    public void send(String payload) {
        // In real scenario vendorUrl should come from properties
        String vendorUrl = "https://vendor.example/api/events";
        restTemplate.postForEntity(vendorUrl, payload, Void.class);
    }
}
