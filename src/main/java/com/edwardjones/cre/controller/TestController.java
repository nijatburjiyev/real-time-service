package com.edwardjones.cre.controller;

import com.edwardjones.cre.model.dto.DesiredConfiguration;
import com.edwardjones.cre.service.bootstrap.ProductionBootstrapService;
import com.edwardjones.cre.service.logic.ComplianceLogicService;
import com.edwardjones.cre.service.reconciliation.ReconciliationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Internal testing controller for manual operations.
 * Kept for internal service debugging and operations.
 */
@Slf4j
@RestController
@RequestMapping("/api/internal")
@RequiredArgsConstructor
public class TestController {

    private final ProductionBootstrapService bootstrapService;
    private final ComplianceLogicService complianceLogicService;
    private final ReconciliationService reconciliationService;

    /**
     * Load initial data from external sources
     */
    @PostMapping("/load-data")
    public ResponseEntity<String> loadData() {
        try {
            log.info("🔄 Manual data loading requested via TestController");
            // Note: Bootstrap service runs automatically on startup, but this endpoint
            // can be used to trigger manual reload if needed
            return ResponseEntity.ok("✅ Bootstrap service runs automatically on application startup");
        } catch (Exception e) {
            log.error("❌ Error during manual data loading", e);
            return ResponseEntity.status(500).body("❌ Data loading failed: " + e.getMessage());
        }
    }

    /**
     * Calculate configuration for a specific user
     */
    @GetMapping("/calculate/{username}")
    public ResponseEntity<DesiredConfiguration> calculateUser(@PathVariable String username) {
        try {
            log.info("🧮 Manual calculation requested for user: {}", username);
            DesiredConfiguration config = complianceLogicService.calculateConfigurationForUser(username);
            return ResponseEntity.ok(config);
        } catch (Exception e) {
            log.error("❌ Error calculating configuration for user {}", username, e);
            return ResponseEntity.status(500).body(null);
        }
    }

    /**
     * Run reconciliation for a specific user
     */
    @PostMapping("/reconcile/{username}")
    public ResponseEntity<String> reconcileUser(@PathVariable String username) {
        try {
            log.info("🔧 Manual reconciliation requested for user: {}", username);
            reconciliationService.reconcileUser(username);
            return ResponseEntity.ok("✅ Reconciliation completed for user: " + username);
        } catch (Exception e) {
            log.error("❌ Error during manual reconciliation for user {}", username, e);
            return ResponseEntity.status(500).body("❌ Reconciliation failed: " + e.getMessage());
        }
    }

    /**
     * Run full reconciliation job manually
     */
    @PostMapping("/reconcile-all")
    public ResponseEntity<String> reconcileAll() {
        try {
            log.info("🌙 Manual full reconciliation requested via TestController");
            reconciliationService.runDailyTrueUp();
            return ResponseEntity.ok("✅ Full reconciliation completed successfully");
        } catch (Exception e) {
            log.error("❌ Error during manual full reconciliation", e);
            return ResponseEntity.status(500).body("❌ Full reconciliation failed: " + e.getMessage());
        }
    }

    /**
     * Health check endpoint
     */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("✅ Service is healthy");
    }
}
