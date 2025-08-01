package com.companya.realtime.integration;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.companya.realtime.integration.exception.PermanentException;
import com.companya.realtime.integration.exception.RetryableException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.RestClientException;

import java.time.Duration;

@Component
public class VendorClient {

    private final RestTemplate restTemplate = new RestTemplate();
    private final String baseUrl;
    private final io.github.resilience4j.ratelimiter.RateLimiter rateLimiter;
    private final CircuitBreaker circuitBreaker;
    private static final Logger log = LoggerFactory.getLogger(VendorClient.class);

    public VendorClient(@Value("${vendor.api.base-url}") String baseUrl,
                        RateLimiterRegistry rlRegistry,
                        CircuitBreakerRegistry cbRegistry) {
        this.baseUrl = baseUrl;

        RateLimiterConfig rlConfig = RateLimiterConfig.custom()
                .limitForPeriod(20)
                .limitRefreshPeriod(Duration.ofMinutes(1))
                .timeoutDuration(Duration.ofSeconds(2))
                .build();
        this.rateLimiter = rlRegistry.rateLimiter("vendorRateLimiter", rlConfig);

        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofMinutes(1))
                .slidingWindowSize(5)
                .build();
        this.circuitBreaker = cbRegistry.circuitBreaker("vendorCircuitBreaker", cbConfig);
    }

    public CircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }

    @Retry(name = "vendorRetry")
    @RateLimiter(name = "vendorRateLimiter")
    @io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker(name = "vendorCircuitBreaker")
    public void sendUpdate(String payload) {
        try {
            restTemplate.postForEntity(baseUrl, payload, Void.class);
            log.info("Sent payload to vendor");
        } catch (HttpStatusCodeException ex) {
            log.error("Vendor 4xx error: {}", ex.getMessage());
            if (ex.getStatusCode().is4xxClientError()) {
                throw new PermanentException("Permanent vendor error", ex);
            }
            throw new RetryableException("Vendor error", ex);
        } catch (RestClientException ex) {
            log.error("Vendor call failed: {}", ex.getMessage());
            throw new RetryableException("Transient vendor error", ex);
        } catch (Exception ex) {
            log.error("Unexpected vendor error: {}", ex.getMessage());
            throw new RetryableException("Unknown vendor error", ex);
        }
    }
}
