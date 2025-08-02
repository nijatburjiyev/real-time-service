# real-time-service

Template Spring Boot middleware service that ingests change data from CSV files and a Kafka topic, applies business rules from an H2 database, and forwards events to a vendor via REST API.

## Build

```bash
./gradlew clean build
```

## Run

```bash
./gradlew bootRun
```

CSV reading schedule, file path, Kafka settings and vendor endpoint can be configured in `src/main/resources/application.properties`.
