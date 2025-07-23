package com.edwardjones.cre.service;

import com.edwardjones.cre.client.VendorApiClient;
import com.edwardjones.cre.model.dto.AdChangeEvent;
import com.edwardjones.cre.model.dto.CrtChangeEvent;
import com.edwardjones.cre.model.dto.DesiredConfiguration;
import com.edwardjones.cre.repository.AppUserRepository;
import com.edwardjones.cre.repository.UserTeamMembershipRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.HashSet;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class KafkaListenerService {

    private final AppUserRepository appUserRepository;
    private final UserTeamMembershipRepository userTeamMembershipRepository;
    private final ComplianceLogicService complianceLogicService;
    private final VendorApiClient vendorApiClient;

    @KafkaListener(topics = "${app.kafka.topics.ad-changes}", groupId = "compliance-sync")
    @Transactional
    public void consumeAdChanges(AdChangeEvent event) {
        log.info("Processing AD change for user: {}", event.getPjNumber());

        appUserRepository.findById(event.getPjNumber()).ifPresent(user -> {
            // Apply change to user entity...
            appUserRepository.save(user);
        });

        Set<String> affectedUsers = new HashSet<>();
        affectedUsers.add(event.getPjNumber());
        appUserRepository.findByManagerUsername(event.getPjNumber())
            .forEach(report -> affectedUsers.add(report.getUsername()));

        processAffectedUsers(affectedUsers);
    }

    @KafkaListener(topics = "${app.kafka.topics.crt-changes}", groupId = "compliance-sync")
    @Transactional
    public void consumeCrtChanges(CrtChangeEvent event) {
        log.info("Processing CRT change for team: {}", event.getCrbtId());

        // In a real implementation, you would update the team and membership tables here.

        Set<String> affectedUsers = new HashSet<>();
        userTeamMembershipRepository.findByTeamCrbtId(event.getCrbtId())
            .forEach(membership -> affectedUsers.add(membership.getUser().getUsername()));

        processAffectedUsers(affectedUsers);
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
}
