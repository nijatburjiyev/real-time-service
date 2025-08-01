# real-time-service

## Overview
This project demonstrates a minimal middleware service built with Spring Boot. It listens to a Kafka topic, performs simple processing and forwards the messages to an external vendor over HTTP. To avoid duplicate pushes and to handle failures gracefully, the application persists processed events and records failed deliveries for manual review.

## Features
1. **Kafka Listener with Manual Acknowledgment** – messages are acknowledged only after successful processing.
2. **Idempotency Tracking** – processed event keys are stored in an H2 database to prevent re‑sending.
3. **Retry, Circuit Breaker and Rate Limiting** – vendor calls use Resilience4j annotations to retry failures, stop traffic when the circuit is open and limit requests to 20 per minute.
4. **Failure Table with Background Retry** – undeliverable events are stored and a scheduled task periodically attempts to resend them.
5. **Manual Pause Flag** – setting `app.vendor.paused` skips vendor calls so an operator can pause and resume processing.

## Running the Application
1. Ensure Kafka is available and update `spring.kafka.bootstrap-servers` and `app.kafka.topic` in `application.properties` if necessary.
2. If `gradle/wrapper/gradle-wrapper.jar` is missing, run `gradle wrapper` once
   to generate it (or use a locally installed Gradle binary).
3. From the project root run:
   ```bash
   ./gradlew bootRun
   ```
4. The application uses an in‑memory H2 database, so data resets on restart.

## Deep Dive
### Kafka Listener
`EventListener` checks whether the incoming event key already exists in `processed_events`. New events are sent to the vendor with the `VendorClient`. Regardless of success or failure, the Kafka offset is acknowledged manually so that messages are not re‑processed accidentally.

### Vendor Client
The client uses Resilience4j `RateLimiter`, `Retry` and `CircuitBreaker` annotations. Requests are limited to 20 per minute, retried up to three times and the circuit opens after repeated errors to avoid hammering the vendor. When the circuit closes again the next successful call resets it. Failed attempts that exhaust the retry limit are persisted to `failed_events`.

### Database Entities
- `ProcessedEvent` – stores unique event keys as proof of successful processing.
- `FailedEvent` – holds events that could not be delivered even after retries.

Both entities are handled via Spring Data JPA repositories and persisted automatically in H2.

### Configuration
Important settings reside in `application.properties`:
- Kafka consumer group, manual ack mode and topic name
- H2 database URL
- Resilience4j retry, circuit breaker and rate limiter parameters
- `app.vendor.paused` and `app.retry.fixed-delay` to control pause mode and background retries

Adjust these values to match your environment or to switch to a different database.

## Testing
Run the unit test with:
```bash
./gradlew test
```
If the wrapper JAR is absent, execute `gradle wrapper` first.
The provided test simply loads the Spring context to verify configuration.

