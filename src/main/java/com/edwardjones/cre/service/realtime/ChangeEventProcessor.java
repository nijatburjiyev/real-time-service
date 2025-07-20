package com.edwardjones.cre.service.realtime;

import com.edwardjones.cre.business.ComplianceLogicService;
import com.edwardjones.cre.client.VendorApiClient;
import com.edwardjones.cre.model.domain.AppUser;
import com.edwardjones.cre.model.dto.AdChangeEvent;
import com.edwardjones.cre.model.dto.CrtChangeEvent;
import com.edwardjones.cre.repository.AppUserRepository;
import com.edwardjones.cre.repository.CrbtTeamRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChangeEventProcessor {
    private final AppUserRepository appUserRepository;
    private final CrbtTeamRepository crbtTeamRepository;
    private final VendorApiClient vendorApiClient;
    private final ComplianceLogicService complianceLogicService;

    @Transactional
    public void processAdChange(AdChangeEvent event) {
        AppUser user = appUserRepository.findById(event.getPjNumber()).orElse(null);
        if (user == null) {
            // User doesn't exist, maybe it's a new user? Handle creation.
            log.warn("User {} not found in local state, skipping change", event.getPjNumber());
            return;
        }

        // --- 1. Update Local State ---
        // Get old configuration BEFORE changing the user
        AppUser oldConfig = complianceLogicService.calculateConfigurationForUser(copyUser(user));

        // Apply the change
        if ("Manager".equalsIgnoreCase(event.getProperty())) {
            String newManager = parseManagerFromDN(event.getNewValue());
            user.setManagerUsername(newManager);
        } else if ("Title".equalsIgnoreCase(event.getProperty())) {
            user.setTitle(event.getNewValue());
        } else if ("FirstName".equalsIgnoreCase(event.getProperty())) {
            user.setFirstName(event.getNewValue());
        } else if ("LastName".equalsIgnoreCase(event.getProperty())) {
            user.setLastName(event.getNewValue());
        }
        // ... handle other properties ...

        appUserRepository.save(user);

        // --- 2. Impact Analysis & Recalculation ---
        // Recalculate based on the NEW state
        AppUser newConfig = complianceLogicService.calculateConfigurationForUser(user);

        // --- 3. Compare and Push ---
        if (!newConfig.getCalculatedGroups().equals(oldConfig.getCalculatedGroups()) ||
            !newConfig.getCalculatedVisibilityProfile().equals(oldConfig.getCalculatedVisibilityProfile())) {
            vendorApiClient.updateUser(newConfig);
        }

        // Handle downstream impact if it was a managerial change
        if ("Manager".equalsIgnoreCase(event.getProperty())) {
            handleManagerialChangeImpact(event.getPjNumber());
        }
    }

    @Transactional
    public void processCrtChange(CrtChangeEvent event) {
        log.info("Processing CRT change for team: {}", event.getCrbtId());
        // TODO: Implement CRT change processing logic
        // This would involve updating team memberships and recalculating affected users
    }

    private AppUser copyUser(AppUser original) {
        // Simple copy method to preserve original state for comparison
        AppUser copy = new AppUser();
        copy.setUsername(original.getUsername());
        copy.setEmployeeId(original.getEmployeeId());
        copy.setFirstName(original.getFirstName());
        copy.setLastName(original.getLastName());
        copy.setTitle(original.getTitle());
        copy.setDistinguishedName(original.getDistinguishedName());
        copy.setManagerUsername(original.getManagerUsername());
        copy.setActive(original.isActive());
        return copy;
    }

    private String parseManagerFromDN(String dn) {
        // Logic to parse "CN=p12345,OU=..." into "p12345"
        if (dn != null && dn.startsWith("CN=")) {
            int commaIndex = dn.indexOf(',');
            if (commaIndex > 3) {
                return dn.substring(3, commaIndex);
            }
        }
        return null;
    }

    private void handleManagerialChangeImpact(String managerUsername) {
        // Find all direct reports and re-process them
        var directReports = appUserRepository.findByManagerUsername(managerUsername);
        for (AppUser directReport : directReports) {
            AppUser updatedConfig = complianceLogicService.calculateConfigurationForUser(directReport);
            vendorApiClient.updateUser(updatedConfig);
        }
    }
}
