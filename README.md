# Real Time Service

This service ingests change events from Kafka, persists them in an internal H2 database and forwards updates to a vendor REST API.  Daily CSV imports and a nightly UI drift check keep the state database in sync with external systems.  The codebase is intentionally lightweight so it can serve as a starting point for more robust integrations.

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

- **KafkaChangeListener** – subscribes to the `changes` topic using manual acknowledgments so records are committed only after processing.
- **RecordService** – stores updates in the `Record` table via Spring Data JPA.
- **VendorClient** – wraps a `RestTemplate` with Resilience4j rate limiting and a circuit breaker. It allows up to 20 requests per minute and temporarily stops calls when the vendor API is failing.
- **VendorIntegrationService** – persists outbound payloads as `OutboundEvent` entities and attempts to send them through `VendorClient`. When the vendor API cannot be reached it pauses the Kafka consumer and periodically retries sending queued events.
- **Jobs** – `DailyCsvSyncJob` and `UiDriftCorrectionJob` are scheduled placeholders for daily CSV imports and nightly reconciliation with the UI.
- **Configuration** – `KafkaManualAckConfig` creates a custom listener container factory enabling manual acknowledgments.

The database is an H2 file located at `./data/realtimedb`. Unsent outbound events survive restarts because they are stored in this database.

## Data Flow

1. **Kafka ingestion** – `KafkaChangeListener` receives a record, parses headers into an `EventType` and converts the payload to JSON.
2. **State update** – depending on the event type, `RecordService` upserts or deletes a `Record` entity.
3. **Vendor update** – `VendorIntegrationService` saves a new `OutboundEvent` row and tries to deliver it via `VendorClient`.
4. **Acknowledgment** – once the payload is persisted and the outbound event queued, the listener manually acknowledges the Kafka message.
5. **Retry loop** – a scheduled task calls `flushPending()` every 30 seconds to retry sending any `PENDING` events. If sending fails the Kafka consumer remains paused; once all pending events are sent it resumes.

This approach ensures that even if the application crashes after persisting an outbound event, the event will be retried on startup without rereading the same Kafka message.

## Edge Cases and Resilience

- **Vendor API outage** – Failures trigger the Resilience4j circuit breaker and pause the Kafka consumer. The retry loop waits one minute (circuit breaker open state) before attempting to send again.
- **Application crash** – Because manual acks occur only after saving the outbound event, a crash may lead to the Kafka message being reprocessed. This creates a duplicate `OutboundEvent` but prevents data loss.
- **High throughput** – With the 20 req/min rate limit, a prolonged vendor outage or a spike in Kafka messages can create a large backlog of `OutboundEvent` rows. They are stored on disk so processing continues when the vendor API recovers.
- **Database failures** – If the H2 database becomes unavailable, message processing will fail before acknowledgment and Kafka will redeliver the record. The application relies on the underlying database to recover.
- **Multiple service instances** – The current implementation assumes a single Kafka consumer instance. Running multiple instances would require coordination for pausing/resuming and for processing the same outbound events.

Overall the service guards against vendor downtime and restarts but does not currently handle database corruption or disk exhaustion.

## Configuration

All configuration values live in `src/main/resources/application.yml`:

```yaml
spring:
  datasource:
    url: jdbc:h2:file:./data/realtimedb
  kafka:
    consumer:
      bootstrap-servers: localhost:9092
      group-id: realtime-service
vendor:
  api:
    base-url: https://vendor.example/api
```

Replace the vendor `base-url` with the real endpoint when deploying.

