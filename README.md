# Compliance Sync Service

A Spring Boot application that provides real-time compliance synchronization for Edward Jones CRE (Compliance Risk Engine) system. This service maintains data consistency between Active Directory, CRT (Client Review Tool), and vendor systems through event-driven architecture and scheduled reconciliation.

## Testing Strategy - Simple Mock-Based Testing

This project uses a **simple, realistic testing approach** where mock data is treated as if it were real data from external APIs and Kafka topics. The tests validate the complete compliance business logic using production-like data without requiring any external systems.

### Core Testing Philosophy

**Test the business logic exactly as it would work in production, but with controlled mock data.**

The mock implementations behave identically to real APIs/Kafka - they just load data from local JSON files instead of making network calls.

### How It Works

#### **Mock Data = Real Data Behavior**
```java
// In production: Real LDAP call
List<AppUser> users = adLdapClient.fetchAllUsers(); // Calls real AD

// In tests: Same method, loads from JSON  
List<AppUser> users = adLdapClient.fetchAllUsers(); // Loads test-ad-users.json
```

The business logic can't tell the difference - it processes the same data structures and follows the same code paths.

#### **Realistic Test Data**
Your mock data files contain production-like structures:

**`test-ad-users.json`** - 13 realistic users:
- Katherine Powell (VP Compliance) 
- David Chen (Senior Analyst)
- Maria Garcia (Branch Leader)
- Financial Advisors, Branch Administrators
- Geographic diversity (US states, Canada)

**`final-crbt-api-response.json`** - 3 realistic teams:
- VTM teams (Visiting Team Members)
- SFA teams (Senior Financial Advisors) 
- HTM teams (Home Team Members)

### Running Tests

#### **Simple Test Execution**
```bash
# Run all tests - validates complete business logic
./gradlew test

# Run specific compliance tests
./gradlew test --tests="DatabaseAnalysisTest"
```

That's it! No complex scripts, no manual setup required.

#### **What the Tests Do**
1. **Load Mock Data**: Reads JSON files as if they came from real APIs
2. **Process Business Logic**: Runs actual compliance calculations
3. **Validate Results**: Ensures users get correct compliance groups and permissions
4. **Verify Data Flow**: Confirms the complete AD → Business Logic → CRT → Database flow works

#### **Expected Results**
After running tests, you'll see:
```
✅ 13 users processed with compliance calculations
✅ 3 teams created with proper member relationships  
✅ 5+ team memberships established
✅ All business rules validated
✅ BUILD SUCCESSFUL
```

### Test Database Analysis (Optional)

If you want to examine the test data in detail:

```bash
# Start app in test mode to access H2 console
SPRING_PROFILES_ACTIVE=test ./gradlew bootRun

# Then visit: http://localhost:8081/h2-console
# JDBC URL: jdbc:h2:file:./data/test_compliance_state_db
# Username: sa, Password: test
```

**Simple queries to verify results:**
```sql
-- See all users with calculated compliance
SELECT username, title, calculated_groups FROM APP_USER;

-- See team memberships
SELECT u.username, u.title, m.team_crbt_id, m.member_role 
FROM APP_USER u 
JOIN USER_TEAM_MEMBERSHIP m ON u.username = m.user_username;
```

### Key Benefits of This Simple Approach

#### **1. No External Dependencies**
- No Active Directory connection needed
- No CRT API endpoints required  
- No Kafka brokers running
- No network connectivity required

#### **2. Production-Identical Logic**
- Same business rules engine
- Same data processing code
- Same calculation algorithms
- Same error handling

#### **3. Fast and Reliable**
- Tests complete in seconds
- Same results every time
- No flaky network issues
- Easy to debug failures

#### **4. Realistic Scenarios**
- Production-like user hierarchies
- Real team structures
- Geographic complexity (US/Canada)
- Multiple role types and relationships

### What Gets Tested

#### **Business Logic Validation**
- ✅ Submitter group assignments (CA-HO, US-BR, etc.)
- ✅ User type classification (HO, BR, HO_LEADER, BR_TEAM)
- ✅ Visibility profile calculations
- ✅ Team membership handling
- ✅ PowerShell-to-Java rule translation

#### **Data Flow Validation** 
- ✅ AD mock data → User entities
- ✅ CRT mock data → Team entities  
- ✅ Business rules → Compliance calculations
- ✅ Database persistence → Relationship integrity

#### **Integration Validation**
- ✅ Complete end-to-end flow
- ✅ Error handling
- ✅ Data transformations
- ✅ Database schema correctness

### Transition to Production

When ready for production, simply:

1. **Replace AD Mock**: Connect to real LDAP instead of JSON file
2. **Replace CRT Mock**: Use actual CRT API instead of JSON file
3. **Enable Kafka**: Set `app.kafka.enabled=true` to activate real-time event processing
4. **Scale Database**: Switch from H2 to PostgreSQL for 30,000+ users

The business logic stays exactly the same - only the data sources change.

### Test Configuration

The test profile automatically:
- ✅ Uses port 8081 (avoids conflicts)
- ✅ Disables Kafka (no external dependencies)
- ✅ Uses test database (`test_compliance_state_db`)
- ✅ Enables detailed logging for debugging
- ✅ Loads realistic mock data instead of making API calls

This simple approach gives you confidence that your compliance logic works correctly before adding the complexity of real external integrations.

## Architecture Overview

The Compliance Sync Service follows a microservices architecture pattern with event-driven design, implementing the following key architectural principles:

- **Event-Driven Architecture**: Uses Apache Kafka for real-time event processing
- **Domain-Driven Design**: Organized around compliance business logic
- **Layered Architecture**: Clear separation between presentation, business, and data layers
- **CQRS Pattern**: Command and Query responsibility segregation for better performance
- **Eventual Consistency**: Maintains data consistency across distributed systems

### High-Level Architecture

```
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│   Active        │    │      CRT        │    │    Vendor       │
│   Directory     │    │   (Client       │    │    Systems      │
│   (LDAP)        │    │  Review Tool)   │    │                 │
└─────────┬───────┘    └─────────┬───────┘    └─────────┬───────┘
          │                      │                      │
          │ AD Events            │ CRT Events           │ API Calls
          ▼                      ▼                      ▼
┌─────────────────────────────────────────────────────────────────┐
│                    Apache Kafka                                 │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐ │
│  │ ad-changes-topic│  │crt-changes-topic│  │     DLT Topic   │ │
│  └─────────────────┘  └─────────────────┘  └─────────────────┘ │
└─────────────────────┬───────────────────────────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────────────────────────┐
│              Compliance Sync Service                            │
│                                                                 │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐ │
│  │  Kafka          │  │   Business      │  │  Reconciliation │ │
│  │  Listeners      │  │   Logic         │  │  Scheduler      │ │
│  └─────────────────┘  └─────────────────┘  └─────────────────┘ │
│                                                                 │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │                H2 Database                                  │ │
│  │  ┌─────────────┐ ┌─────────────┐ ┌─────────────────────┐   │ │
│  │  │  AppUser    │ │  CrbtTeam   │ │ UserTeamMembership  │   │ │
│  │  └─────────────┘ └─────────────┘ └─────────────────────┘   │ │
│  └─────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────┘
```

## Component-Level Design

### 1. Core Components

#### **ComplianceSyncApplication**
- Main Spring Boot application entry point
- Enables scheduling for reconciliation processes
- Configures application context and auto-configuration

#### **Business Logic Layer**
- **ComplianceLogicService**: Core business rules engine that translates PowerShell compliance logic to Java
  - Calculates desired configuration for users based on business rules
  - Manages submitter groups mapping
  - Determines user types and visibility profiles
  - Orchestrates compliance calculations

#### **Client Layer**
- **AdLdapClient**: Interface to Active Directory via LDAP
  - Fetches user data from AD
  - Handles authentication and user attribute retrieval
  - Currently uses mock implementation for testing
- **CrbtApiClient**: Interface to CRT (Client Review Tool) API
  - Retrieves team and membership data
  - Handles CRT-specific business entities
- **VendorApiClient**: Interface to external vendor systems
  - Manages vendor-specific compliance requirements
  - Handles API communication with third-party systems

### 2. Real-Time Processing Components

#### **ComplianceKafkaListener**
- Consumes events from Kafka topics
- Implements retry logic with exponential backoff
- Routes events to appropriate processors
- Handles dead letter topic (DLT) for failed messages

#### **ChangeEventProcessor**
- Processes AD and CRT change events
- Applies business logic to determine compliance impact
- Updates local state and triggers vendor synchronization
- Manages transactional integrity

### 3. Data Layer

#### **Domain Models**
- **AppUser**: Represents user entities with compliance attributes
- **CrbtTeam**: Represents teams in CRT system
- **UserTeamMembership**: Represents many-to-many relationships between users and teams
- **UserTeamMembershipId**: Composite key for membership relationships

#### **Repository Layer**
- **AppUserRepository**: Data access for user entities
- **UserTeamMembershipRepository**: Data access for team memberships
- JPA-based repositories with H2 database persistence

### 4. Service Layer Organization

#### **Bootstrap Services**
- Handle initial data loading and system initialization
- Populate database with baseline data from external systems

#### **Mock Services**
- Provide test implementations for external dependencies
- Enable development and testing without external system dependencies

#### **Reconciliation Services**
- Scheduled processes for data consistency
- Full synchronization between systems
- Drift detection and correction

## Data Flow Examples

### 1. Real-Time AD User Change Processing

```
AD User Modified → AD System publishes event → Kafka (ad-changes-topic)
                                                      ↓
ComplianceKafkaListener.consumeAdChanges() ← Kafka Consumer
                    ↓
            ChangeEventProcessor.processAdChange()
                    ↓
         ComplianceLogicService.calculateConfigurationForUser()
                    ↓
              Update Local Database (H2)
                    ↓
           VendorApiClient.syncUserToVendor()
```

**Example Flow:**
1. User "PJ12345" gets promoted and their AD attributes change
2. AD system publishes `AdChangeEvent` to `ad-changes-topic`
3. `ComplianceKafkaListener` receives the event with retry capability
4. `ChangeEventProcessor` validates and processes the change
5. `ComplianceLogicService` recalculates user's compliance configuration
6. Local H2 database is updated with new user state
7. `VendorApiClient` synchronizes changes to external vendor systems

### 2. CRT Team Change Processing

```
CRT Team Modified → CRT System publishes event → Kafka (crt-changes-topic)
                                                        ↓
ComplianceKafkaListener.consumeCrtChanges() ← Kafka Consumer
                    ↓
            ChangeEventProcessor.processCrtChange()
                    ↓
         Update affected team and user memberships
                    ↓
    Recalculate compliance for all affected users
                    ↓
           Sync changes to vendor systems
```

**Example Flow:**
1. Team "TEAM001" membership changes in CRT
2. CRT publishes `CrtChangeEvent` to `crt-changes-topic`
3. Service processes team change and identifies affected users
4. Business logic recalculates compliance for all team members
5. Vendor systems are updated with new team configurations

### 3. Scheduled Reconciliation Process

```
Cron Trigger (2 AM daily) → Reconciliation Service
                                    ↓
                          Fetch all data from AD
                                    ↓
                          Fetch all data from CRT
                                    ↓
                    Compare with local database state
                                    ↓
                      Identify and log discrepancies
                                    ↓
                    Apply corrections and sync vendors
```

**Example Flow:**
1. Daily at 2 AM, reconciliation process starts
2. Service fetches complete user list from AD via `AdLdapClient`
3. Service fetches complete team data from CRT via `CrbtApiClient`
4. System compares external data with local H2 database state
5. Discrepancies are identified and logged
6. Corrections are applied and synchronized to vendor systems

### 4. Error Handling and Recovery

```
Event Processing Failure → Retry with exponential backoff
                                    ↓
              Max retries exceeded → Dead Letter Topic (DLT)
                                    ↓
                          Manual intervention required
                                    ↓
                          Replay from DLT when ready
```

## Configuration

### Kafka Topics
- **ad-changes-topic**: User change events from Active Directory
- **crt-changes-topic**: Team change events from CRT system
- **compliance-sync.dlt**: Dead letter topic for failed message processing

### Database
- **H2 File Database**: Persistent storage at `./data/compliance_state_db`
- **Auto-schema generation**: Tables created/updated on startup
- **SQL logging**: Enabled for debugging and monitoring

### Scheduling
- **Reconciliation**: Daily at 2 AM (`0 0 2 * * ?`)
- **Configurable**: Can be adjusted via `app.reconciliation.cron` property

## Key Features

- **Event-Driven Real-time Processing**: Immediate response to AD and CRT changes
- **Fault Tolerance**: Retry mechanisms and dead letter topics for reliability
- **Business Rule Engine**: Centralized compliance logic translation from PowerShell
- **Multi-System Integration**: Seamless data flow between AD, CRT, and vendor systems
- **Scheduled Reconciliation**: Daily consistency checks and drift correction
- **Monitoring and Logging**: Comprehensive logging for audit and debugging
- **Mock Implementations**: Development-friendly test doubles for external dependencies

This architecture ensures high availability, data consistency, and real-time compliance synchronization across the Edward Jones CRE ecosystem.
