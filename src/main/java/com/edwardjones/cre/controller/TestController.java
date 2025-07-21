package com.edwardjones.cre.controller;

import com.edwardjones.cre.service.logic.ComplianceLogicService;
import com.edwardjones.cre.client.VendorApiClient;
import com.edwardjones.cre.model.domain.AppUser;
import com.edwardjones.cre.repository.AppUserRepository;
import com.edwardjones.cre.service.mock.MockKafkaEventSimulator;
import com.edwardjones.cre.service.reconciliation.ReconciliationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Test controller for manually triggering mock scenarios and monitoring the application.
 * Updated to use the new service/logic package structure.
 */
@Slf4j
@RestController
@RequestMapping("/api/test")
@RequiredArgsConstructor
public class TestController {

    private final MockKafkaEventSimulator mockKafkaEventSimulator;
    private final ComplianceLogicService complianceLogicService;
    private final VendorApiClient vendorApiClient;
    private final AppUserRepository appUserRepository;
    private final ReconciliationService reconciliationService;

    /**
     * Trigger mock Kafka events to test real-time processing
     */
    @PostMapping("/trigger-events/{scenario}")
    public ResponseEntity<Map<String, Object>> triggerEvents(@PathVariable String scenario) {
        log.info("Manual trigger requested for scenario: {}", scenario);

        Map<String, Object> response = new HashMap<>();
        response.put("scenario", scenario);
        response.put("timestamp", System.currentTimeMillis());

        try {
            mockKafkaEventSimulator.triggerSpecificScenario(scenario);
            response.put("status", "success");
            response.put("message", "Events triggered successfully");
        } catch (Exception e) {
            log.error("Error triggering scenario: {}", scenario, e);
            response.put("status", "error");
            response.put("message", e.getMessage());
        }

        return ResponseEntity.ok(response);
    }

    /**
     * Test the compliance logic for a specific user
     */
    @GetMapping("/calculate-config/{username}")
    public ResponseEntity<Map<String, Object>> calculateUserConfig(@PathVariable String username) {
        log.info("Manual calculation requested for user: {}", username);

        Map<String, Object> response = new HashMap<>();
        response.put("username", username);

        try {
            AppUser calculatedUser = complianceLogicService.calculateConfigurationForUser(username);

            Map<String, Object> config = new HashMap<>();
            config.put("username", calculatedUser.getUsername());
            config.put("firstName", calculatedUser.getFirstName());
            config.put("lastName", calculatedUser.getLastName());
            config.put("title", calculatedUser.getTitle());
            config.put("country", calculatedUser.getCountry());
            config.put("managerUsername", calculatedUser.getManagerUsername());
            config.put("calculatedVisibilityProfile", calculatedUser.getCalculatedVisibilityProfile());
            config.put("calculatedGroups", calculatedUser.getCalculatedGroups());

            response.put("status", "success");
            response.put("configuration", config);

        } catch (Exception e) {
            log.error("Error calculating config for user: {}", username, e);
            response.put("status", "error");
            response.put("message", e.getMessage());
        }

        return ResponseEntity.ok(response);
    }

    /**
     * Get all users and their current calculated configurations
     */
    @GetMapping("/users/all-configs")
    public ResponseEntity<Map<String, Object>> getAllUserConfigs() {
        log.info("Retrieving all user configurations");

        Map<String, Object> response = new HashMap<>();

        try {
            List<AppUser> allUsers = appUserRepository.findAll();
            List<Map<String, Object>> userConfigs = allUsers.stream()
                .map(user -> {
                    AppUser calculatedUser = complianceLogicService.calculateConfigurationForUser(user.getUsername());

                    Map<String, Object> config = new HashMap<>();
                    config.put("username", calculatedUser.getUsername());
                    config.put("name", calculatedUser.getFirstName() + " " + calculatedUser.getLastName());
                    config.put("title", calculatedUser.getTitle());
                    config.put("country", calculatedUser.getCountry());
                    config.put("manager", calculatedUser.getManagerUsername());
                    config.put("visibilityProfile", calculatedUser.getCalculatedVisibilityProfile());
                    config.put("groups", calculatedUser.getCalculatedGroups());
                    return config;
                })
                .toList();

            response.put("status", "success");
            response.put("totalUsers", userConfigs.size());
            response.put("users", userConfigs);

        } catch (Exception e) {
            log.error("Error retrieving user configurations", e);
            response.put("status", "error");
            response.put("message", e.getMessage());
        }

        return ResponseEntity.ok(response);
    }

    /**
     * Trigger manual reconciliation to test the nightly process
     */
    @PostMapping("/trigger-reconciliation")
    public ResponseEntity<Map<String, Object>> triggerReconciliation() {
        log.info("Manual reconciliation triggered");

        Map<String, Object> response = new HashMap<>();
        response.put("timestamp", System.currentTimeMillis());

        try {
            reconciliationService.runDailyTrueUp();
            response.put("status", "success");
            response.put("message", "Reconciliation completed successfully");
        } catch (Exception e) {
            log.error("Error during manual reconciliation", e);
            response.put("status", "error");
            response.put("message", e.getMessage());
        }

        return ResponseEntity.ok(response);
    }

    /**
     * Get vendor API call statistics
     */
    @GetMapping("/vendor-api/stats")
    public ResponseEntity<Map<String, Object>> getVendorApiStats() {
        Map<String, Object> response = new HashMap<>();
        response.put("statistics", vendorApiClient.getCallStatistics());
        response.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.ok(response);
    }

    /**
     * Reset vendor API call counters
     */
    @PostMapping("/vendor-api/reset-counters")
    public ResponseEntity<Map<String, Object>> resetVendorApiCounters() {
        vendorApiClient.resetCounters();

        Map<String, Object> response = new HashMap<>();
        response.put("status", "success");
        response.put("message", "Vendor API counters reset");
        response.put("timestamp", System.currentTimeMillis());

        return ResponseEntity.ok(response);
    }

    /**
     * Health check endpoint
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "healthy");
        response.put("timestamp", System.currentTimeMillis());
        response.put("totalUsers", appUserRepository.count());
        return ResponseEntity.ok(response);
    }
}
