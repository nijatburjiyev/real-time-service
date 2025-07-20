package com.edwardjones.cre.service.reconciliation;

import com.edwardjones.cre.business.ComplianceLogicService;
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
        log.info("--- RUNNING NIGHTLY RECONCILIATION ---");

        try {
            // 1. Fetch current state from Vendor
            var actualUsers = vendorApiClient.getAllUsers();
            var actualGroups = vendorApiClient.getAllGroups();
            var actualVisibilityProfiles = vendorApiClient.getAllVisibilityProfiles();

            // 2. Fetch desired state from H2 and calculate what it should be
            var desiredUsers = appUserRepository.findAll();

            // 3. Compare and correct drift
            reconcileUsers(desiredUsers);

            log.info("--- RECONCILIATION COMPLETE ---");

        } catch (Exception e) {
            log.error("Error during nightly reconciliation: ", e);
            // TODO: Send alert/notification about reconciliation failure
        }
    }

    private void reconcileUsers(Iterable<AppUser> desiredUsers) {
        for (AppUser user : desiredUsers) {
            try {
                // Calculate what the user's configuration should be
                AppUser calculatedConfig = complianceLogicService.calculateConfigurationForUser(user);

                // TODO: Compare with actual vendor state and push corrections
                // For now, just push the calculated configuration
                vendorApiClient.updateUser(calculatedConfig);

            } catch (Exception e) {
                log.error("Error reconciling user {}: ", user.getUsername(), e);
                // Continue with other users even if one fails
            }
        }
    }
}
