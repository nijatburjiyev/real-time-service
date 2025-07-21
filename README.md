# Compliance Sync Service

This Spring Boot project simulates a real-time synchronization service that keeps a vendor compliance platform in sync with internal data sources. It loads sample data for development and demonstrates how Kafka events and scheduled tasks drive the synchronization process.

## Project Goals

- Maintain an internal state database of users, CRBT teams and memberships.
- Process change events from Active Directory (AD) and CRBT (CRT) feeds in real time.
- Calculate each user's required vendor groups and visibility profile using business rules.
- Push updates to the vendor API whenever a user or team changes.
- Provide a nightly reconciliation job to detect and correct drift.

## Features

- **Bootstrap** – on startup, loads mock AD users and CRBT teams into an H2 database and builds team memberships.
- **Real‑time processing** – `ComplianceKafkaListener` receives AD and CRT change events and delegates to `ChangeEventProcessor` to apply them.
- **Business logic engine** – `ComplianceLogicService` translates complex PowerShell rules into Java and determines a user's groups and visibility profile.
- **Mock vendor integration** – `VendorApiClient` logs outbound updates, simulating calls to the external vendor system.
- **Manual testing APIs** – `TestController` exposes endpoints to trigger scenarios and inspect calculated configurations.
- **Nightly reconciliation** – `ReconciliationService` (scheduled via cron) recalculates all users and updates the vendor state.

## Requirements

- Java 21
- Gradle (or use the Gradle wrapper if added)

Kafka is configured in `application.yml` but the included mocks allow running the application without a Kafka broker for local testing.

## Building and Running

```bash
# run tests
gradle test

# start the application
gradle bootRun
```

The service uses an embedded H2 database located under `./data` and will create or update the schema automatically.

## Usage Examples

After starting the service you can access the following endpoints:

- `POST /api/test/trigger-events/{scenario}` – fire predefined mock Kafka events (`manager_change`, `team_change`, etc.).
- `GET /api/test/calculate-config/{username}` – return the calculated configuration for a single user.
- `GET /api/test/users/all-configs` – list every user with their calculated configuration.
- `POST /api/test/trigger-reconciliation` – manually start the nightly reconciliation job.
- `GET /api/test/vendor-api/stats` – view counts of mock vendor API calls.
- `POST /api/test/vendor-api/reset-counters` – reset vendor API call statistics.
- `GET /api/test/health` – basic health check.

## Key Modules

| Path | Description |
| ---- | ----------- |
| `ComplianceSyncApplication.java` | Spring Boot entry point. Enables scheduling. |
| `ComplianceLogicService.java` | Core business rules for groups and visibility profiles. |
| `ChangeEventProcessor.java` | Applies AD/CRT events and pushes vendor updates. |
| `BootstrapService.java` | Loads mock users, teams and memberships on startup. |
| `ReconciliationService.java` | Scheduled job that recalculates all users nightly. |
| `TestController.java` | REST endpoints for manual testing. |

## Contributing

1. Fork the repository and create a feature branch.
2. Follow the existing code style (Spring Boot with Lombok). Prefer constructor injection for new services.
3. Include tests for any new functionality (`gradle test`).
4. Open a pull request describing your changes.

## License

No license file is present. Assume all rights reserved unless a license is added.
