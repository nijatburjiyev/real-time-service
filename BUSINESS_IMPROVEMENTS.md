# Recommended Business Logic Improvements

This document contains a high level review of the core logic with suggestions for future refactoring.

## 1. Parameterise Submitter Groups

`ComplianceLogicService` defines `SUBMITTER_GROUPS` as a hard coded `Map` (lines 29–35).
Loading these mappings from configuration or a database would allow updates without code changes and improve testability.

## 2. User Type Detection

The method `determineUserType` (lines 84–101) mixes checks on the user's distinguished name and title with direct database calls. Consider separating these checks into dedicated helper methods and using an enum or strategy pattern. This will clarify the branching logic and make the rules easier to extend.

## 3. Multiple Team Configuration

`generateConfigurationFromMultipleGroups` (lines 192–238) directly translates the precedence rules from PowerShell using nested loops. The current implementation builds `finalVpName` from the last group in a `HashSet`, which is not deterministic. Sorting the teams before building the name or storing explicit priority values would make the results predictable. Splitting this method into smaller helpers (e.g. `processVtmTeams`, `processHtmTeams`) would also improve readability.

## 4. Change Event Application

`ChangeEventProcessor.applyAdChangeToUser` performs a large switch on string property names. Introducing an enum of known properties or a map of handlers would reduce the risk of typos and make unhandled cases clearer. The new user creation flow is currently marked TODO and should be implemented so that the system can self-populate new entries from AD.

## 5. Reconciliation Logic

`ReconciliationService` recalculates each user and unconditionally calls `vendorApiClient.updateUser`. Implement a comparison with the vendor's actual state (fetched at the start of `runDailyTrueUp`) to avoid unnecessary updates and to detect discrepancies. The current placeholders for vendor groups and visibility profiles can be expanded into full diff logic.

## 6. Error Handling and Logging

Several services log warnings for missing users or teams. Adding more structured error handling (custom exceptions or Spring `ApplicationEvent`s) could allow centralised alerts. Consider reducing log verbosity once the system is stable or adding log levels via configuration.
