package com.edwardjones.cre.service.reconciliation;

import com.edwardjones.cre.service.logic.ComplianceLogicService;
import com.edwardjones.cre.client.VendorApiClient;
import com.edwardjones.cre.model.domain.AppUser;
import com.edwardjones.cre.repository.AppUserRepository;
import com.edwardjones.cre.repository.CrbtTeamRepository;
import com.edwardjones.cre.repository.UserTeamMembershipRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;

/**
 * Handles nightly reconciliation to correct configuration drift.
 * Now delegates all business logic to ComplianceLogicService.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReconciliationService {

    private final AppUserRepository appUserRepository;
    private final CrbtTeamRepository crbtTeamRepository;
    private final UserTeamMembershipRepository userTeamMembershipRepository;
    private final VendorApiClient vendorApiClient;
    private final ComplianceLogicService complianceLogicService;

    @Scheduled(cron = "${app.reconciliation.cron}")
    @Transactional
    public void runDailyTrueUp() {
        log.info("🌙 === STARTING NIGHTLY RECONCILIATION ===");

        try {
            // 1. Fetch current state from Vendor (for future drift detection)
            var actualUsers = vendorApiClient.getAllUsers();
            var actualGroups = vendorApiClient.getAllGroups();
            var actualVisibilityProfiles = vendorApiClient.getAllVisibilityProfiles();

            log.info("📊 Vendor state: {} users, {} groups, {} VPs",
                    actualUsers.size(), actualGroups.size(), actualVisibilityProfiles.size());

            // 2. Get all users from our state database
            List<AppUser> allUsers = appUserRepository.findAll();
            log.info("📋 Processing {} users from state database", allUsers.size());

            // 3. Delegate bulk processing to ComplianceLogicService
            complianceLogicService.recalculateAndPushUpdates(new HashSet<>(allUsers));

            log.info("✅ === NIGHTLY RECONCILIATION COMPLETE ===");

        } catch (Exception e) {
            log.error("❌ Error during nightly reconciliation: ", e);
            // TODO: Send alert/notification about reconciliation failure
            throw e; // Re-throw to ensure the failure is recorded
        }
    }
}
