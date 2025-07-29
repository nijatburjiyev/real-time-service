# Real Time Service new

This project provides a skeleton Spring Boot microservice that consumes real-time changes from Kafka, applies daily updates from a CSV file and performs nightly drift correction against the UI. All updates are persisted in a database (State DB) and propagated to a vendor system via REST API.

## Building

```bash
gradle build
```

## Running

```bash
gradle bootRun
```

The application exposes a health endpoint at `http://localhost:8080/actuator/health`.
