package com.edwardjones.cre.service;

import com.edwardjones.cre.client.VendorApiClient;
import com.edwardjones.cre.model.domain.AppUser;
import com.edwardjones.cre.model.dto.AdChangeEvent;
import com.edwardjones.cre.model.dto.CrtChangeEvent;
import com.edwardjones.cre.model.dto.DesiredConfiguration;
import com.edwardjones.cre.repository.AppUserRepository;
import com.edwardjones.cre.repository.UserTeamMembershipRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.HashSet;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "spring.kafka.listener.auto-startup", havingValue = "true", matchIfMissing = true)
public class KafkaListenerService {

    private final AppUserRepository appUserRepository;
    private final UserTeamMembershipRepository userTeamMembershipRepository;
    private final ComplianceLogicService complianceLogicService;
    private final VendorApiClient vendorApiClient;

    @KafkaListener(topics = "${app.kafka.topics.ad-changes}", groupId = "compliance-sync")
    @Transactional
    public void consumeAdChanges(AdChangeEvent event) {
        String username = event.getPjNumber();
        log.info("Processing AD change event for user: {}", username);

        try {
            // Step 1: Update State
            if ("TerminatedUser".equalsIgnoreCase(event.getChangeType())) {
                appUserRepository.findById(username).ifPresent(user -> {
                    user.setActive(false);
                    appUserRepository.save(user);
                });
            } else {
                // For new users or data changes, update or create the user record
                AppUser user = appUserRepository.findById(username).orElse(new AppUser());
                user.setUsername(username);
                // Apply the specific change from the event
                applyAdChangeToUser(user, event);
                appUserRepository.save(user);
            }

            // Step 2: Determine Blast Radius
            Set<String> affectedUsers = new HashSet<>();
            affectedUsers.add(username);
            if (event.isManagerialChange()) {
                appUserRepository.findByManagerUsername(username)
                    .forEach(report -> affectedUsers.add(report.getUsername()));
            }

            // Step 3: Process and Push
            processAffectedUsers(affectedUsers);

        } catch (Exception e) {
            log.error("Failed to process AD change event for user: {}", username, e);
            throw e; // Re-throw to trigger Kafka retry mechanism
        }
    }

    @KafkaListener(topics = "${app.kafka.topics.crt-changes}", groupId = "compliance-sync")
    @Transactional
    public void consumeCrtChanges(CrtChangeEvent event) {
        Integer teamId = event.getCrbtId();
        log.info("Processing CRT change for team: {}", teamId);

        try {
            // Step 1: Update State
            // This is where you would add logic to update the CrbtTeam and UserTeamMembership tables
            // For example, if a member is leaving, you would delete the membership record.
            // If a new member is added, you would create a new membership record.
            log.warn("CRT change state update logic is a placeholder and needs to be fully implemented.");

            // Step 2: Determine Blast Radius
            Set<String> affectedUsers = new HashSet<>();
            userTeamMembershipRepository.findByTeamCrbtId(teamId)
                .forEach(membership -> affectedUsers.add(membership.getUser().getUsername()));

            // Step 3: Process and Push
            processAffectedUsers(affectedUsers);

        } catch (Exception e) {
            log.error("Failed to process CRT change event for team: {}", teamId, e);
            throw e;
        }
    }

    private void processAffectedUsers(Set<String> usernames) {
        for (String username : usernames) {
            try {
                DesiredConfiguration config = complianceLogicService.calculateConfigurationForUser(username);
                vendorApiClient.updateUser(config);
            } catch (Exception e) {
                log.error("Failed to process update for user {}. Will be corrected by reconciliation.", username, e);
            }
        }
    }

    private void applyAdChangeToUser(AppUser user, AdChangeEvent event) {
        String property = event.getProperty();
        String newValue = event.getNewValue();

        if (property == null) return;

        switch (property) {
            case AdChangeEvent.PROPERTY_TITLE -> user.setTitle(newValue);
            case AdChangeEvent.PROPERTY_MANAGER -> user.setManagerUsername(newValue); // Assuming newValue is the PJ number
            case AdChangeEvent.PROPERTY_DISTINGUISHED_NAME -> user.setDistinguishedName(newValue);
            case AdChangeEvent.PROPERTY_EJ_IR_NUMBER -> user.setEmployeeId(newValue);
            case AdChangeEvent.PROPERTY_STATE -> user.setCountry(newValue);
            case AdChangeEvent.PROPERTY_ENABLED -> user.setActive(Boolean.parseBoolean(newValue));
            // Add other impactful properties here as needed
            default -> log.debug("Unhandled property change: {} for user: {}", property, user.getUsername());
        }
    }
}
