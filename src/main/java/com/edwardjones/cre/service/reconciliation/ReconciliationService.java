package com.edwardjones.cre.service.reconciliation;

import com.edwardjones.cre.client.VendorApiClient;
import com.edwardjones.cre.client.VendorApiClient.VendorUser;
import com.edwardjones.cre.model.dto.DesiredConfiguration;
import com.edwardjones.cre.repository.AppUserRepository;
import com.edwardjones.cre.service.logic.ComplianceLogicService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Clean reconciliation service that uses the single source of truth for compliance logic.
 *
 * Responsibilities:
 * - Run scheduled reconciliation jobs
 * - Compare actual vendor state with desired state
 * - Correct any drift found in the vendor system
 *
 * Uses the same ComplianceLogicService as real-time processing to ensure consistency.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReconciliationService {

    private final AppUserRepository appUserRepository;
    private final ComplianceLogicService complianceLogicService;
    private final VendorApiClient vendorApiClient;

    @Scheduled(cron = "${app.reconciliation.cron}")
    @Transactional(readOnly = true)
    public void runDailyTrueUp() {
        log.info("[RECON] Starting nightly reconciliation job...");

        AtomicInteger processedCount = new AtomicInteger(0);
        AtomicInteger driftCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        try {
            // 1. Fetch actual state from vendor system
            log.info("[RECON] Phase 1/3: Fetching current vendor state...");
            Map<String, VendorUser> actualUserMap = vendorApiClient.getAllUsers().stream()
                    .collect(Collectors.toMap(VendorUser::getUsername, u -> u));
            log.info("[RECON] Phase 1/3 Complete: Retrieved {} users from vendor system", actualUserMap.size());

            // 2. Iterate through all users in our state database
            log.info("[RECON] Phase 2/3: Processing users for reconciliation...");
            appUserRepository.findAll().forEach(user -> {
                try {
                    // 3. Calculate desired state using the single source of truth
                    DesiredConfiguration desired = complianceLogicService.calculateConfigurationForUser(user.getUsername());
                    VendorUser actual = actualUserMap.get(user.getUsername());

                    processedCount.incrementAndGet();

                    // 4. Compare and correct if needed
                    if (actual == null) {
                        log.warn("[RECON][DRIFT] User {} missing in vendor. Creating.", desired.username());
                        vendorApiClient.createUser(desired);
                        driftCount.incrementAndGet();
                    } else if (hasDrifted(desired, actual)) {
                        log.warn("[RECON][DRIFT] User {} requires correction. Desired: [VP={}, Groups={}], Actual: [VP={}, Groups={}]",
                            desired.username(), desired.visibilityProfile(), desired.groups(),
                            actual.getVisibilityProfile(), actual.getGroups().stream().map(VendorUser.GroupRef::getGroupName).collect(Collectors.toSet()));
                        vendorApiClient.updateUser(desired);
                        driftCount.incrementAndGet();
                    } else {
                        log.debug("[RECON] User {} is in sync", desired.username());
                    }

                    // Progress logging
                    if (processedCount.get() % 100 == 0) {
                        log.info("[RECON] Progress: {} users processed, {} corrections made",
                                processedCount.get(), driftCount.get());
                    }

                } catch (Exception e) {
                    log.error("[RECON][ERROR] Failed to reconcile user '{}': {}", user.getUsername(), e.getMessage(), e);
                    errorCount.incrementAndGet();
                    // Continue processing other users - don't re-throw
                }
            });

            log.info("[RECON] Phase 3/3: Reconciliation analysis complete");

        } catch (Exception e) {
            log.error("[RECON][CRITICAL] A fatal error occurred during the reconciliation job, terminating early.", e);
            // It's okay to let the job fail here if fetching from the vendor fails, for example.
        } finally {
            // This block ensures you ALWAYS get a summary report, even if the job fails halfway through.
            log.info("---------- Reconciliation Job Summary ----------");
            log.info("[RECON] Users Processed: {}", processedCount.get());
            log.info("[RECON] Drifts Corrected: {}", driftCount.get());
            log.info("[RECON] Record Errors:    {}", errorCount.get());
            log.info("----------------------------------------------");
        }
    }

    /**
     * Determines if the vendor state has drifted from the desired state.
     */
    private boolean hasDrifted(DesiredConfiguration desired, VendorUser actual) {
        // Check active status
        if (desired.isActive() != actual.isActive()) {
            log.debug("Drift detected - Active status: desired={}, actual={}", desired.isActive(), actual.isActive());
            return true;
        }

        // Check visibility profile
        if (!desired.visibilityProfile().equals(actual.getVisibilityProfile())) {
            log.debug("Drift detected - Visibility profile: desired='{}', actual='{}'",
                    desired.visibilityProfile(), actual.getVisibilityProfile());
            return true;
        }

        // Check groups
        Set<String> actualGroups = actual.getGroups().stream()
                .map(VendorUser.GroupRef::getGroupName)
                .collect(Collectors.toSet());

        if (!desired.groups().equals(actualGroups)) {
            log.debug("Drift detected - Groups: desired={}, actual={}", desired.groups(), actualGroups);
            return true;
        }

        return false;
    }

    /**
     * Manual reconciliation trigger for specific user (can be called by TestController)
     */
    @Transactional(readOnly = true)
    public void reconcileUser(String username) {
        log.info("🔧 Manual reconciliation requested for user: {}", username);

        try {
            DesiredConfiguration desired = complianceLogicService.calculateConfigurationForUser(username);

            // Get current vendor state for this user
            VendorUser actual = vendorApiClient.getAllUsers().stream()
                    .filter(u -> u.getUsername().equals(username))
                    .findFirst()
                    .orElse(null);

            if (actual == null) {
                log.info("🆕 Creating missing user in vendor: {}", username);
                vendorApiClient.createUser(desired);
            } else if (hasDrifted(desired, actual)) {
                log.info("🔧 Correcting drift for user: {}", username);
                vendorApiClient.updateUser(desired);
            } else {
                log.info("✅ User {} is already in sync", username);
            }

        } catch (Exception e) {
            log.error("❌ Error during manual reconciliation for user {}: {}", username, e.getMessage(), e);
            throw new RuntimeException("Manual reconciliation failed for user: " + username, e);
        }
    }
}
