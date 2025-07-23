package com.edwardjones.cre.service;

import com.edwardjones.cre.client.VendorApiClient;
import com.edwardjones.cre.model.dto.DesiredConfiguration;
import com.edwardjones.cre.repository.AppUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReconciliationService {

    private final AppUserRepository appUserRepository;
    private final ComplianceLogicService complianceLogicService;
    private final VendorApiClient vendorApiClient;

    @Scheduled(cron = "${app.reconciliation.cron}")
    @Transactional(readOnly = true)
    public void runDailyTrueUp() {
        log.info("Starting nightly reconciliation job...");

        Map<String, VendorApiClient.VendorUser> actualUserMap = vendorApiClient.getAllUsers().stream()
            .collect(Collectors.toMap(VendorApiClient.VendorUser::getUsername, u -> u));

        appUserRepository.findByIsActiveTrue().forEach(user -> {
            try {
                DesiredConfiguration desired = complianceLogicService.calculateConfigurationForUser(user.getUsername());
                VendorApiClient.VendorUser actual = actualUserMap.get(user.getUsername());

                if (actual == null) {
                    log.warn("[RECON] User {} missing in vendor. Creating.", desired.username());
                    vendorApiClient.createUser(desired);
                } else if (hasDrifted(desired, actual)) {
                    log.warn("[RECON] User {} has drifted. Correcting.", desired.username());
                    vendorApiClient.updateUser(desired);
                }
            } catch (Exception e) {
                log.error("[RECON] Failed to reconcile user '{}'", user.getUsername(), e);
            }
        });
        log.info("Nightly reconciliation job complete.");
    }

    private boolean hasDrifted(DesiredConfiguration desired, VendorApiClient.VendorUser actual) {
        if (desired.isActive() != actual.isActive()) return true;
        if (!desired.visibilityProfile().equals(actual.getVisibilityProfile())) return true;

        Set<String> actualGroups = actual.getGroups().stream()
            .map(VendorApiClient.VendorUser.GroupRef::getGroupName)
            .collect(Collectors.toSet());
        return !desired.groups().equals(actualGroups);
    }
}
