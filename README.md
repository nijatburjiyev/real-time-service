# Real Time Service new
 
This project provides a skeleton Spring Boot microservice that consumes real-time changes from Kafka, applies daily updates from a CSV file and performs nightly drift correction against the UI. All updates are persisted in a database (StateDB) and propagated to a vendor system via REST API.

## Building

```bash
mvn package
```

## Running

```bash
mvn spring-boot:run
```

The application exposes a health endpoint at `http://localhost:8080/actuator/health`.
