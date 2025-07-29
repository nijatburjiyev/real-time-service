package com.companya.realtime.integration;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.function.Supplier;

@Component
public class VendorClient {

    private final RestTemplate restTemplate = new RestTemplate();
    private final String baseUrl;
    private final RateLimiter rateLimiter;
    private final CircuitBreaker circuitBreaker;

    public VendorClient(@Value("${vendor.api.base-url}") String baseUrl) {
        this.baseUrl = baseUrl;

        RateLimiterConfig rlConfig = RateLimiterConfig.custom()
                .limitForPeriod(20)
                .limitRefreshPeriod(Duration.ofMinutes(1))
                .timeoutDuration(Duration.ofSeconds(2))
                .build();
        this.rateLimiter = RateLimiter.of("vendorRateLimiter", rlConfig);

        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofMinutes(1))
                .slidingWindowSize(5)
                .build();
        this.circuitBreaker = CircuitBreaker.of("vendorCircuitBreaker", cbConfig);
    }

    public void sendUpdate(String payload) {
        Supplier<Void> supplier = () -> {
            restTemplate.postForEntity(baseUrl, payload, Void.class);
            return null;
        };

        Supplier<Void> rateLimited = RateLimiter.decorateSupplier(rateLimiter, supplier);
        Supplier<Void> decorated = CircuitBreaker.decorateSupplier(circuitBreaker, rateLimited);
        decorated.get();
    }
}
