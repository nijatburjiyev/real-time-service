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
        log.info("🌙 Starting nightly reconciliation job...");

        AtomicInteger processedCount = new AtomicInteger(0);
        AtomicInteger driftCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        try {
            // 1. Fetch actual state from vendor system
            log.info("📥 Fetching current vendor state...");
            Map<String, VendorUser> actualUserMap = vendorApiClient.getAllUsers().stream()
                    .collect(Collectors.toMap(VendorUser::getUsername, u -> u));
            log.info("📥 Retrieved {} users from vendor system", actualUserMap.size());

            // 2. Iterate through all users in our state database
            log.info("🔄 Processing users for reconciliation...");
            appUserRepository.findAll().forEach(user -> {
                try {
                    // 3. Calculate desired state using the single source of truth
                    DesiredConfiguration desired = complianceLogicService.calculateConfigurationForUser(user.getUsername());
                    VendorUser actual = actualUserMap.get(user.getUsername());

                    processedCount.incrementAndGet();

                    // 4. Compare and correct if needed
                    if (actual == null) {
                        log.warn("[RECON] User {} missing in vendor. Creating.", desired.username());
                        vendorApiClient.createUser(desired);
                        driftCount.incrementAndGet();
                    } else if (hasDrifted(desired, actual)) {
                        log.warn("[RECON] User {} has drifted. Correcting.", desired.username());
                        vendorApiClient.updateUser(desired);
                        driftCount.incrementAndGet();
                    } else {
                        log.debug("[RECON] User {} is in sync", desired.username());
                    }

                    // Progress logging
                    if (processedCount.get() % 100 == 0) {
                        log.info("🔄 Reconciliation progress: {} users processed, {} corrections made",
                                processedCount.get(), driftCount.get());
                    }

                } catch (Exception e) {
                    log.error("❌ Error during reconciliation for user {}: {}", user.getUsername(), e.getMessage(), e);
                    errorCount.incrementAndGet();
                }
            });

            log.info("✅ Nightly reconciliation job complete:");
            log.info("   📊 {} users processed", processedCount.get());
            log.info("   🔧 {} corrections made", driftCount.get());
            log.info("   ❌ {} errors encountered", errorCount.get());

        } catch (Exception e) {
            log.error("💥 Critical error during reconciliation job", e);
            throw new RuntimeException("Reconciliation job failed", e);
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
