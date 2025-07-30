package com.companya.realtime.integration;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.function.Supplier;

@Component
public class VendorClient {

    private static final Logger logger = LoggerFactory.getLogger(VendorClient.class);

    private final RestTemplate restTemplate = new RestTemplate();
    private final String baseUrl;
    private final RateLimiter rateLimiter;
    private final CircuitBreaker circuitBreaker;

    public VendorClient(@Value("${vendor.api.base-url}") String baseUrl) {
        this.baseUrl = baseUrl;
        logger.info("Initializing VendorClient with base URL: {}", baseUrl);

        RateLimiterConfig rlConfig = RateLimiterConfig.custom()
                .limitForPeriod(20)
                .limitRefreshPeriod(Duration.ofMinutes(1))
                .timeoutDuration(Duration.ofSeconds(2))
                .build();
        this.rateLimiter = RateLimiter.of("vendorRateLimiter", rlConfig);
        logger.info("Rate limiter configured: 20 requests per minute");

        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofMinutes(1))
                .slidingWindowSize(5)
                .minimumNumberOfCalls(3)
                .build();
        this.circuitBreaker = CircuitBreaker.of("vendorCircuitBreaker", cbConfig);
        logger.info("Circuit breaker configured: 50% failure threshold, 1 minute open state");

        // Add circuit breaker event listeners for better observability
        circuitBreaker.getEventPublisher()
                .onStateTransition(event ->
                    logger.warn("Circuit breaker state transition: {} -> {}",
                               event.getStateTransition().getFromState(),
                               event.getStateTransition().getToState()));

        circuitBreaker.getEventPublisher()
                .onCallNotPermitted(event ->
                    logger.warn("Circuit breaker call not permitted - circuit is open"));
    }

    public void sendUpdate(String payload) {
        logger.debug("Attempting to send update to vendor: {}", payload);

        Supplier<Void> supplier = () -> {
            try {
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                HttpEntity<String> request = new HttpEntity<>(payload, headers);

                restTemplate.postForEntity(baseUrl, request, Void.class);
                logger.debug("Successfully sent update to vendor");
                return null;
            } catch (Exception ex) {
                logger.error("Vendor API call failed: {}", ex.getMessage());
                throw ex;
            }
        };

        try {
            Supplier<Void> rateLimited = RateLimiter.decorateSupplier(rateLimiter, supplier);
            Supplier<Void> decorated = CircuitBreaker.decorateSupplier(circuitBreaker, rateLimited);
            decorated.get();
            logger.info("Successfully sent update to vendor via circuit breaker and rate limiter");
        } catch (Exception ex) {
            logger.error("Failed to send update to vendor after applying resilience patterns: {}", ex.getMessage());
            throw ex;
        }
    }
}
