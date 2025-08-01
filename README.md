# Real Time Service

This service consumes change events from Kafka and forwards updates to a vendor REST API. The design relies solely on Kafka for delivery guarantees and uses Resilience4j to handle transient failures with retries, rate limiting and a circuit breaker. No local database is required.

## Building

```bash
gradle build
```

## Running

```bash
gradle bootRun
```

The application exposes a health endpoint at `http://localhost:8080/actuator/health`.

## Architecture

The service is built on Spring Boot and uses the following modules:

- **KafkaChangeListener** – consumes the `changes` topic with manual acknowledgments and validates each record before processing. Permanent failures are published to a `.DLT` topic.
- **VendorClient** – calls the vendor API and is protected by Resilience4j annotations for retry, rate limiting and a circuit breaker.
- **VendorIntegrationService** – delegates to `VendorClient` and pauses or resumes the Kafka consumer when the circuit breaker opens or closes. Metrics track sent and failed events.
- **Configuration** – `KafkaManualAckConfig` provides a listener container factory with a `DefaultErrorHandler` that stops retries after a short backoff and routes messages to the dead letter topic.

## Data Flow

1. **Kafka ingestion** – `KafkaChangeListener` parses headers into an `EventType` and converts the payload to JSON.
2. **Vendor update** – the payload is passed to `VendorIntegrationService` which issues the REST call.
3. **Acknowledgment** – the listener manually acknowledges the record only when processing completes successfully or the message is sent to the dead letter topic.

## Resilience

- **Poison messages** – malformed JSON is immediately sent to the dead letter topic and acknowledged.
- **Vendor API outage** – the circuit breaker pauses the consumer while open; records are retried a limited number of times by the `DefaultErrorHandler`.
- **Back-pressure** – when the circuit breaker transitions back to half-open or closed, the consumer is resumed.

## Configuration

Key settings live in `src/main/resources/application.yml`:

```yaml
spring:
  kafka:
    consumer:
      bootstrap-servers: localhost:9092
      group-id: realtime-service
vendor:
  api:
    base-url: https://vendor.example/api
resilience4j:
  retry:
    instances:
      vendorRetry:
        maxAttempts: 3
        waitDuration: 1s
  ratelimiter:
    instances:
      vendorRateLimiter:
        limitForPeriod: 20
        limitRefreshPeriod: 1m
        timeoutDuration: 2s
  circuitbreaker:
    instances:
      vendorCircuitBreaker:
        failureRateThreshold: 50
        waitDurationInOpenState: 60s
        slidingWindowSize: 5
```

Replace the vendor `base-url` with the real endpoint when deploying.
