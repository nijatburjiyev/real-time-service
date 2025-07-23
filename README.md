# Compliance Sync Service

## 1. Project Overview

The **Compliance Sync Service** is a real-time, event-driven microservice responsible for synchronizing user, group, and visibility profile configurations between Edward Jones's internal systems (Active Directory, CRBT) and an external vendor's compliance platform.

This service replaces a legacy batch-oriented PowerShell script with a modern, robust, and maintainable Java application. Its primary goal is to ensure that the vendor's platform accurately reflects the complex hierarchies and business rules defined internally, providing near real-time updates and guaranteeing long-term data consistency.

---

## 2. Architectural Design

The service is built using a clean, service-oriented architecture that emphasizes simplicity, maintainability, and the Single Responsibility Principle.

### Core Architectural Pillars

1.  **Stateful Processing:** The service maintains its own persistent state in an embedded H2 file-based database. This "local cache" of the user and team hierarchy is the service's single source of truth, allowing for high-speed impact analysis without constantly querying source systems.
2.  **Event-Driven:** The primary mechanism for updates is asynchronous, via two Kafka topics that stream Change Data Capture (CDC) events from Active Directory and the CRBT system.
3.  **Three-Phase Lifecycle:** The service operates in three distinct phases to ensure robustness:
    *   **Phase 1: Bootstrap:** On initial startup, the service performs a full data load from LDAP and the CRBT API to build its initial state in the H2 database.
    *   **Phase 2: Real-Time Processing:** The service continuously listens to Kafka topics, updates its local state, recalculates configurations for affected users, and pushes targeted updates to the vendor.
    *   **Phase 3: Nightly Reconciliation:** A scheduled job runs daily to compare the desired state (in H2) with the actual state (in the vendor's system) and automatically correct any "drift" caused by manual UI changes or missed events.

### Component-Level Design

The project is organized into clear, decoupled components:

*   **`client`:** Interfaces and implementations for communicating with external systems (LDAP, CRBT API, Vendor API).
*   **`model`:** Contains the data structures:
    *   `domain`: JPA entities that map directly to the H2 state database tables.
    *   `dto`: Data Transfer Objects for Kafka messages and vendor API payloads.
*   **`repository`:** Spring Data JPA interfaces for all database operations.
*   **`service`:** The core of the application, with four distinct responsibilities:
    *   `BootstrapService`: Handles the one-time data load at startup.
    *   `ComplianceLogicService`: **The brain.** A pure, stateless service containing all the business rules translated from the original PowerShell scripts.
    *   `KafkaListenerService`: Listens to Kafka topics and orchestrates the real-time update workflow.
    *   `ReconciliationService`: Executes the nightly true-up job.

---

## 3. Under the Hood: Processes and Low-Level Information

### State Database Schema

The H2 database consists of three primary tables:

1.  **`APP_USER`**: Stores user data from AD, including the critical `manager_username` (for hierarchy) and the calculated `is_financial_advisor` flag.
2.  **`CRBT_TEAM`**: Stores team data from the CRBT API.
3.  **`USER_TEAM_MEMBERSHIP`**: A join table linking users to the teams they are members of.

### Real-Time Workflow (Kafka Event)

1.  A message is consumed by `KafkaListenerService`.
2.  The listener updates the relevant entity in the H2 database.
3.  It queries the H2 database to determine the "blast radius" (all users affected by the change).
4.  For each affected user, it calls `ComplianceLogicService` to calculate the new `DesiredConfiguration`.
5.  It sends the new configuration to the vendor via the `VendorApiClient`.

---

## 4. Latest Business Logic Rules (Summary)

The `ComplianceLogicService` implements the following key rules, validated against the latest PowerShell scripts and raw data samples:

1.  **User Classification:** Users are classified as Home Office (HO), Branch (BR), or Hybrid (HOBR) based on their `distinguishedName` and `title` attributes from Active Directory.
2.  **Leader Identification:** A user is considered a Leader if their AD record contains the `directReports` attribute (in our system, this is checked by seeing if anyone lists them as a manager).
3.  **Financial Advisor (FA) Identification:** A user is a Financial Advisor (`is_financial_advisor = true`) if and only if they are **both** a Leader and a Branch user.
4.  **Standard Visibility Profiles:** Non-leader users receive a standard Visibility Profile based on their classification and country (e.g., `Vis-US-HO`, `Vis-CA-BR`).
5.  **Multi-Team Visibility Profile Precedence:** For users who are members of multiple teams, a special Visibility Profile is dynamically generated. This logic **only considers teams whose members are Financial Advisors**. The final profile is determined by the highest-ranking team type in the following order of precedence: **VTM > HTM > SFA**.

---
## 5. Getting Started

1.  **Configuration:** Update `src/main/resources/application.yml` with the correct credentials and URLs for LDAP, CRBT, and the Vendor API. Use environment variables for sensitive values.
2.  **Build:** From the project root, run `./gradlew build`.
3.  **Run:** Execute the application with `java -jar build/libs/compliance-sync-service-1.0.0-SNAPSHOT.jar`.

The application will automatically perform the bootstrap process on its first run (if the database is empty) and then begin listening for Kafka events.
