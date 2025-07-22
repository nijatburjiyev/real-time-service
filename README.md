# Compliance Synchronization Service (compliance-sync-service)

## 1. Overview

The Compliance Synchronization Service is a real-time, event-driven Java microservice responsible for keeping the third-party Vendor Compliance System perfectly in sync with Edward Jones's internal systems of record: Active Directory (AD/LDAP) and the CRBT Team System.

This service replaces a legacy set of procedural PowerShell scripts with a modern, robust, and maintainable architecture. It ensures that user access, group memberships, and visibility profiles within the compliance application are always an accurate reflection of an employee's current role, team, and management structure.

### Core Responsibilities:

- **Initial State Bootstrap**: On first startup, it performs a full data load from AD and CRBT to build a complete, correct "desired state" of the vendor system.

- **Real-Time Event Processing**: It continuously listens to Kafka topics for Change Data Capture (CDC) events from AD and CRBT, processing changes in near real-time.

- **Impact Analysis**: For every change, it intelligently determines the full "blast radius" (e.g., a manager's title change affects all their direct reports) and recalculates all affected configurations.

- **Vendor API Integration**: It pushes calculated updates for Users, Groups, and Visibility Profiles to the vendor's REST API.

- **State Reconciliation**: It runs a nightly job to detect and correct any "state drift" caused by manual changes made directly in the vendor's UI, ensuring long-term data consistency.

## 2. Architectural Design

The service is built on a **Stateful, Event-Driven Architecture**. This hybrid model provides the speed of real-time processing with the safety and consistency of a traditional batch system.

### High-Level Architecture Diagram
```
+-----------------+      +---------------------+      +-----------------------------+      +---------------------+
|   Active        |----->|   AD CDC Producer   |----->|      ad-user-changes        |      |                     |
|   Directory     |      +---------------------+      |      (Kafka Topic)          |----->|                     |
+-----------------+                                   +-----------------------------+      |                     |
                                                                                             | Compliance Sync     |----> Vendor API
+-----------------+      +---------------------+      +-----------------------------+      | Service             |
|   CRBT System   |----->|  CRBT CDC Producer  |----->|    crt-team-changes         |      | (This Application)  |
|   (REST API)    |      +---------------------+      |      (Kafka Topic)          |----->|                     |
+-----------------+                                   +-----------------------------+      +----------|----------+
                                                                                                      |
                                                                                                      | (Reads/Writes)
                                                                                                      |
                                                                                             +----------V----------+
                                                                                             |   H2 State Database |
                                                                                             | (Local File Cache)  |
                                                                                             +---------------------+
```

### The Three Core Architectural Pillars

#### 1. Stateful Core (The H2 Database):
- The service maintains its own local, persistent state in an embedded H2 file-based database (`compliance_state_db`).
- This is not a full replica. It is a purpose-built cache containing only the data necessary to run the business logic without querying the source systems (AD/CRBT) for every event.
- This stateful design is the key to performance and efficiency. It allows the "Impact Analysis Engine" to answer questions like "Who reports to this manager?" with a fast, local SQL query instead of a slow, remote LDAP query.

#### 2. Event-Driven Processing (Kafka Consumers):
- The service is driven by events. It is idle until a message arrives on one of the two Kafka topics.
- This makes the system highly efficient and scalable. Processing is done in small, targeted chunks as changes occur.
- Error handling is managed with a Dead Letter Topic (DLT). If a message fails processing after several retries, it is moved to the DLT for manual investigation, preventing a single bad message from halting the entire pipeline.

#### 3. Scheduled Reconciliation (The Safety Net):
- To combat "state drift" from manual UI changes in the vendor system, a nightly scheduled job runs.
- This job performs a full "true-up," comparing the service's desired state (from H2) with the vendor's actual state (from their API) and overwriting any discrepancies. This guarantees eventual consistency.

## 3. Component-Level Design

The project follows the principles of **Clean Architecture**, separating concerns into distinct, independent layers.

### Key Packages and Their Responsibilities:

#### `client/`:
- Contains interfaces (`AdLdapClient`, `CrbtApiClient`, `VendorApiClient`) that define contracts for communicating with external systems.
- The `impl/` sub-package contains mock implementations for testing and development.
- The `impl/production/` sub-package contains real implementations for production deployment.

#### `model/`:
- **`domain/`**: Contains the pure JPA entity classes (`AppUser`, `CrbtTeam`) that map directly to the H2 state database tables.
- **`dto/`**: Contains Data Transfer Objects. These are used for deserializing Kafka messages (`AdChangeEvent`, `CrtChangeEvent`) and representing the final calculated state (`DesiredConfiguration`) to be sent to the vendor.

#### `repository/`:
- Contains Spring Data JPA interfaces (`AppUserRepository`, etc.) for all database operations.
- This abstracts away the SQL, providing clean, method-based access to the H2 database.

#### `service/`: This is the heart of the application.
- **`bootstrap/BootstrapService.java`**: Runs once on application startup to perform the initial data load.
- **`logic/ComplianceLogicService.java`**: The Brain. Contains the pure, stateless business logic translated from the original PowerShell scripts.
- **`realtime/`**: Contains the Kafka listener (`ComplianceKafkaListener`) and the event orchestrator (`ChangeEventProcessor`).
- **`reconciliation/ReconciliationService.java`**: Contains the nightly `@Scheduled` job for performing the true-up reconciliation.

#### `config/`:
- **`ApiClientConfig.java`**: Configures REST clients with proper authentication (API keys, cookies).
- **`LdapConfig.java`**: Configures LDAP connection for Active Directory integration.

## 4. Deployment Modes

### Test Mode (Development)
- Uses mock implementations for all external systems
- Loads test data from JSON files in `src/test/resources/test-data/`
- Activated with `--spring.profiles.active=test`

### Production Mode
- Uses real LDAP, CRBT API, and Vendor API clients
- Requires environment variables for authentication
- Automatically activated when not in test profile

## 5. Under the Hood: Processes and Low-Level Information

### The Bootstrap Process

**Trigger**: Runs automatically when the application starts if the H2 database is empty.

**Execution**:
1. It first clears all tables to ensure a clean slate.
2. It calls `AdLdapClient` to fetch all users.
3. **Important**: To handle the manager-report relationship (a circular dependency), it performs a two-stage user save:
   - All users are saved first with their `manager_username` field set to NULL.
   - A second pass updates each user to set the correct `manager_username`, now that all potential managers exist in the database.
4. It calls `CrbtApiClient` to fetch all teams and their members, populating the `CRBT_TEAM` and `USER_TEAM_MEMBERSHIP` tables.

### Real-Time Event Processing: An AD Manager Change

1. **Event Arrival**: A Kafka message arrives for user `p100001` with Property: "Manager" and NewValue: "CN=p999999,...".

2. **State Update**: `ChangeEventProcessor` parses the new manager's PJ number (`p999999`) and runs an UPDATE on the `APP_USER` table in H2 for user `p100001`. This transaction is committed immediately.

3. **Impact Analysis**: The processor identifies the scope of affected users.
   - The user themselves (`p100001`) is always affected.
   - Because it's a manager change, it also queries H2 for users who report to the affected user.

4. **Business Logic Delegation**: For each affected username, it calls `ComplianceLogicService.calculateConfigurationForUser(username)`.

5. **Calculation**: The `ComplianceLogicService` reads the newly updated state from H2 and applies the business rules to generate a new `DesiredConfiguration` object.

6. **Vendor Push**: The `ChangeEventProcessor` receives the DTO and calls `vendorApiClient.updateUser(config)`, pushing the change to the vendor.

### The Reconciliation Job

**Trigger**: A CRON expression (`@Scheduled`) triggers the job at 2 AM daily.

**Process**:
1. **Fetch**: It fetches all users from the vendor API and all users from the local H2 database.
2. **Compare**: For each user, it calculates their "desired" configuration using `ComplianceLogicService` and performs a deep comparison against the data from the vendor.
3. **Correction**: If drift is detected, it calls `vendorApiClient.updateUser()` with the correct configuration, overwriting the manual change.

## 6. Business Logic Rules

The service implements complex business rules for determining user configurations:

### User Classification
- **Branch (BR)**: Users with branch-related titles and branch distinguished names
- **Home Office (HO)**: Users located in home office organizational units
- **Hybrid (HOBR)**: Users with branch titles but home office locations (or vice versa)
- **Leader**: Users with direct reports
- **Team Member**: Users belonging to CRBT teams

### Configuration Calculation
- **Visibility Profiles**: Determined by user type, location, and team membership
- **Group Memberships**: Based on country, role, and organizational structure
- **Special Cases**: Leaders get personalized profiles including their direct reports

## 7. Configuration and Environment Setup

### Required Environment Variables

```bash
# LDAP Configuration
LDAP_URL=ldap://ad.edwardjones.com:389
LDAP_BASE=DC=edj,DC=ad,DC=edwardjones,DC=com
LDAP_USERNAME=CN=service-account,OU=Service Accounts,DC=edj,DC=ad,DC=edwardjones,DC=com
LDAP_PASSWORD=your-secure-ldap-password

# CRBT API Configuration
CRBT_API_BASE_URL=https://crbt-api.edwardjones.com/api/v1
CRBT_EJAUTH_TOKEN=your-ejauth-token
CRBT_EJPKY_TOKEN=your-ejpky-token

# Vendor API Configuration
VENDOR_API_BASE_URL=https://vendor-api.example.com/api/v1
VENDOR_API_KEY=your-vendor-api-key

# Kafka Configuration (optional, defaults provided)
KAFKA_BOOTSTRAP_SERVERS=localhost:9092
```

### Application Profiles
- **`test`**: Uses mock implementations, loads test data
- **`local`**: Development profile with H2 console enabled
- **`production`**: Production profile with real client implementations

## 8. How to Support and Maintain

### Common Support Scenarios

#### A User's Permissions are Incorrect:

1. **Check the State Database**: Use the H2 Console (available at `/h2-console` in the local profile) to inspect the `APP_USER` and `USER_TEAM_MEMBERSHIP` tables for the user.

2. **Trigger a Manual Calculation**: Use the TestController endpoint `GET /api/internal/calculate/{username}` to see what the `ComplianceLogicService` calculates based on the current state.

3. **Trigger a Manual Reconciliation**: If the state and calculation are correct, use `POST /api/internal/reconcile/{username}` to force a correction for that specific user.

#### The Service is Lagging Behind Kafka Topics:

- Check the logs for slow database queries or slow responses from the vendor API.
- Monitor the application's CPU and memory usage.
- If the vendor API is slow, the issue is external. If internal processing is slow, the `ComplianceLogicService` may need optimization.

#### Bootstrap Issues:

- Check LDAP connectivity and credentials
- Verify CRBT API authentication tokens
- Review bootstrap logs for specific error messages

### Monitoring and Health Checks

#### Key Metrics to Monitor:
- Kafka consumer lag
- Database connection pool health
- API response times and error rates
- Bootstrap completion status
- Reconciliation job execution

#### Health Endpoints:
- `/actuator/health` - Overall application health
- `/actuator/metrics` - Application metrics
- `/h2-console` - Database console (local profile only)

### Troubleshooting

#### Common Issues:

**LDAP Connection Failures**:
```
ERROR: Failed to connect to LDAP server
```
- Verify LDAP URL and port accessibility
- Check service account credentials
- Ensure proper network connectivity

**Kafka Consumer Issues**:
```
ERROR: Failed to process Kafka message
```
- Check Kafka broker connectivity
- Verify topic names and consumer group configuration
- Review Dead Letter Topic for failed messages

**Vendor API Errors**:
```
ERROR: Failed to update user in vendor system
```
- Verify API key validity
- Check vendor API endpoint availability
- Review rate limiting policies

### Maintenance and Deployment

#### Dependencies:
- Java 21 or higher
- Access to AD/LDAP server
- Network access to CRBT and Vendor REST APIs
- Access to Kafka cluster

#### Deployment Steps:
1. Set required environment variables
2. Choose appropriate Spring profile
3. Start the application: `java -jar compliance-sync-service.jar`
4. Monitor logs for successful bootstrap completion
5. Verify health endpoints

#### Configuration Management:
- All environment-specific settings are in `application.yml`
- Use Spring Profiles (`local`, `dev`, `prod`) to manage configuration for different environments
- Sensitive credentials should be provided via environment variables

#### Logging:
- The service uses SLF4J for logging
- Log levels can be adjusted in `application.yml`
- Key events like event processing, reconciliation drift, and errors are logged with clear markers (`[RECON]`, `[KAFKA]`, `[BOOTSTRAP]`)

## 9. Testing

### Unit Tests
- Mock implementations ensure tests run without external dependencies
- Comprehensive test coverage for business logic
- Test data provided in `src/test/resources/test-data/`

### Integration Tests
- `UnifiedComplianceIntegrationTest` validates end-to-end workflows
- Tests cover bootstrap, real-time processing, and reconciliation scenarios
- Automated test execution with `./gradlew test`

### Test Data
- **`test-ad-users.json`**: Sample Active Directory user data
- **`final-crbt-api-response.json`**: Sample CRBT team and member data
- **`test-ad-change-events.json`**: Sample Kafka change events

## 10. Performance and Scalability

### Performance Characteristics:
- **Bootstrap**: Typically completes in 30-60 seconds for 10,000+ users
- **Real-time Processing**: Sub-second response times for individual changes
- **Reconciliation**: Nightly job completes in 5-15 minutes depending on user count

### Scalability Considerations:
- H2 database suitable for up to ~50,000 users
- For larger deployments, consider migrating to PostgreSQL
- Kafka partitioning can improve parallel processing
- Vendor API rate limits may require throttling

## 11. Security

### Authentication Methods:
- **LDAP**: Service account with minimal required permissions
- **CRBT API**: Session-based cookie authentication
- **Vendor API**: API key authentication

### Security Best Practices:
- Store credentials in secure vaults (Azure Key Vault, HashiCorp Vault)
- Use environment variables for configuration
- Never commit credentials to source control
- Regular credential rotation
- Network security with VPN or private networks

## 12. Support and Contact Information

For production issues:
1. Check application logs first
2. Verify external system connectivity
3. Review configuration settings
4. Contact infrastructure team for network issues
5. Escalate to development team for logic issues

### Useful Resources:
- **Production Setup Guide**: `PRODUCTION_SETUP.md`
- **Application Logs**: Standard output and configured log files
- **H2 Console**: `/h2-console` (local profile only)
- **Health Checks**: `/actuator/health`
