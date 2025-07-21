package com.edwardjones.cre.service.realtime;

import com.edwardjones.cre.business.ComplianceLogicService;
import com.edwardjones.cre.client.VendorApiClient;
import com.edwardjones.cre.model.domain.AppUser;
import com.edwardjones.cre.model.domain.CrbtTeam;
import com.edwardjones.cre.model.domain.UserTeamMembership;
import com.edwardjones.cre.model.domain.UserTeamMembershipId;
import com.edwardjones.cre.model.dto.AdChangeEvent;
import com.edwardjones.cre.model.dto.CrtChangeEvent;
import com.edwardjones.cre.repository.AppUserRepository;
import com.edwardjones.cre.repository.CrbtTeamRepository;
import com.edwardjones.cre.repository.UserTeamMembershipRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChangeEventProcessor {

    private static final Pattern PJ_NUMBER_PATTERN = Pattern.compile("CN=((p|j)\\d{5,6}),", Pattern.CASE_INSENSITIVE);

    private final AppUserRepository appUserRepository;
    private final CrbtTeamRepository crbtTeamRepository;
    private final UserTeamMembershipRepository userTeamMembershipRepository;
    private final ComplianceLogicService complianceLogicService;
    private final VendorApiClient vendorApiClient;

    /**
     * Processes a change event from Active Directory.
     * This method is transactional to ensure atomicity of state updates.
     *
     * @param event The deserialized Kafka message for an AD change.
     */
    @Transactional
    public void processAdChange(AdChangeEvent event) {
        log.info("Processing AD change for user '{}', changeType '{}', property '{}'",
                event.getPjNumber(), event.getChangeType(), event.getProperty());

        // Handle different change types
        if (event.isNewUser()) {
            processNewUserEvent(event);
            return;
        }

        if (event.isTerminatedUser()) {
            processTerminatedUserEvent(event);
            return;
        }

        // Handle DataChange events for existing users
        if (event.isDataChange()) {
            processDataChangeEvent(event);
            return;
        }

        log.warn("Unknown AD change type '{}' for user '{}'", event.getChangeType(), event.getPjNumber());
    }

    /**
     * Process new user creation events
     */
    private void processNewUserEvent(AdChangeEvent event) {
        log.info("Processing new user creation: {}", event.getPjNumber());

        // Check if user already exists (duplicate event handling)
        Optional<AppUser> existingUser = appUserRepository.findById(event.getPjNumber());
        if (existingUser.isPresent()) {
            log.warn("NewUser event received for existing user: {}", event.getPjNumber());
            return;
        }

        // For new users, we would typically need to fetch their full profile from AD
        // For now, log that a new user needs to be created
        log.info("New user {} requires full profile fetch and creation", event.getPjNumber());
        // TODO: Implement full user profile fetch and creation logic
    }

    /**
     * Process user termination events
     */
    private void processTerminatedUserEvent(AdChangeEvent event) {
        log.info("Processing user termination: {}", event.getPjNumber());

        Optional<AppUser> userOpt = appUserRepository.findById(event.getPjNumber());
        if (userOpt.isEmpty()) {
            log.warn("TerminatedUser event received for non-existent user: {}", event.getPjNumber());
            return;
        }

        AppUser user = userOpt.get();
        user.setActive(false);
        appUserRepository.save(user);

        // Calculate final configuration and push deactivation to vendor
        AppUser finalConfig = complianceLogicService.calculateConfigurationForUser(user.getUsername());
        vendorApiClient.updateUser(finalConfig);

        log.info("User {} deactivated and vendor updated", event.getPjNumber());
    }

    /**
     * Process data change events for existing users
     */
    private void processDataChangeEvent(AdChangeEvent event) {
        log.info("Processing data change for user '{}', property '{}'", event.getPjNumber(), event.getProperty());

        Optional<AppUser> userOpt = appUserRepository.findById(event.getPjNumber());
        if (userOpt.isEmpty()) {
            log.warn("DataChange event received for non-existent user: {}", event.getPjNumber());
            return;
        }

        AppUser user = userOpt.get();

        // --- 1. Capture Old State & Identify Impact Scope ---
        AppUser oldConfigUser = complianceLogicService.calculateConfigurationForUser(user.getUsername());
        Set<String> affectedUsernames = new HashSet<>();
        affectedUsernames.add(user.getUsername());

        // --- 2. Apply the Change to the Local State Database ---
        boolean isImpactfulChange = applyAdChangeToUser(user, event);
        if (!isImpactfulChange) {
            log.info("DataChange for user '{}' property '{}' is not impactful", user.getUsername(), event.getProperty());
            return;
        }

        appUserRepository.save(user);

        // --- 3. Perform Impact Analysis ---
        if (isImpactfulChange) {
            List<AppUser> directReports = appUserRepository.findByManagerUsername(user.getUsername());
            if (directReports != null && !directReports.isEmpty()) {
                log.info("Change to leader {} is impactful. Queueing {} direct reports for reprocessing.",
                        user.getUsername(), directReports.size());
                directReports.forEach(report -> affectedUsernames.add(report.getUsername()));
            }
        }

        // --- 4. Recalculate and Push Updates for All Affected Users ---
        log.info("Recalculating and pushing updates for {} affected users.", affectedUsernames.size());
        affectedUsernames.forEach(username -> {
            if (username.equals(user.getUsername())) {
                AppUser newConfigUser = complianceLogicService.calculateConfigurationForUser(username);
                if (configurationChanged(oldConfigUser, newConfigUser)) {
                    log.info("Configuration for user {} has changed. Pushing update.", username);
                    vendorApiClient.updateUser(newConfigUser);
                } else {
                    log.info("Configuration for user {} did not change. No update needed.", username);
                }
            } else {
                // For downstream users (direct reports), recalculate and push unconditionally
                AppUser reportConfig = complianceLogicService.calculateConfigurationForUser(username);
                vendorApiClient.updateUser(reportConfig);
            }
        });
    }


    /**
     * Helper method to apply changes from an AD event to a user entity.
     *
     * @return true if the change is "impactful" (i.e., could affect direct reports).
     */
    private boolean applyAdChangeToUser(AppUser user, AdChangeEvent event) {
        switch (event.getProperty().toLowerCase()) {
            case "manager":
                user.setManagerUsername(parsePjFromDn(event.getNewValue()));
                return false; // Changing a user's manager doesn't impact their reports.
            case "title":
                user.setTitle(event.getNewValue());
                return true; // A leader's title change can change their team's group name.
            case "distinguishedname":
                user.setDistinguishedName(event.getNewValue());
                return true; // A leader's OU change could affect report configurations.
            case "enabled":
                user.setActive("true".equalsIgnoreCase(event.getNewValue()));
                return false;
            case "ej-irnumber":
                user.setEmployeeId(event.getNewValue());
                return false; // Employee ID changes typically don't impact direct reports
            case "state":
                user.setCountry(event.getNewValue());
                return true; // State/location changes could affect compliance groups
            case "name":
                // For Name changes, this is typically handled by NewUser/TerminatedUser events
                // but we'll log it for tracking purposes
                log.info("Name change detected for user {}: {} -> {}",
                        user.getUsername(), event.getBeforeValue(), event.getNewValue());
                return false;
            default:
                log.warn("Received unhandled AD property change for user {}: {}", user.getUsername(), event.getProperty());
                return false;
        }
    }

    /**
     * Utility to parse a PJ Number from a full Active Directory Distinguished Name.
     * E.g., "CN=p123456,OU=Branch,..." -> "p123456"
     */
    private String parsePjFromDn(String dn) {
        if (dn == null || dn.isEmpty()) {
            return null;
        }
        Matcher matcher = PJ_NUMBER_PATTERN.matcher(dn);
        if (matcher.find()) {
            return matcher.group(1);
        }
        log.warn("Could not parse PJ Number from Distinguished Name: {}", dn);
        return null;
    }

    /**
     * Helper method to compare user configurations for changes
     * Uses optimized comparison that only calculates what's needed
     */
    private boolean configurationChanged(AppUser oldConfig, AppUser newConfig) {
        if (oldConfig == null || newConfig == null) {
            return true; // If either is null, consider it changed
        }

        // First check basic database fields (fast comparison)
        boolean basicFieldsChanged = !Objects.equals(oldConfig.getTitle(), newConfig.getTitle()) ||
               !Objects.equals(oldConfig.getManagerUsername(), newConfig.getManagerUsername()) ||
               !Objects.equals(oldConfig.getDistinguishedName(), newConfig.getDistinguishedName()) ||
               !Objects.equals(oldConfig.getCountry(), newConfig.getCountry()) ||
               oldConfig.isActive() != newConfig.isActive();

        if (basicFieldsChanged) {
            return true; // No need to check calculated fields if basic fields changed
        }

        // Only check calculated fields if basic fields are the same
        // This optimizes performance by avoiding expensive calculations when not needed
        return !Objects.equals(oldConfig.getCalculatedVisibilityProfile(), newConfig.getCalculatedVisibilityProfile()) ||
               !Objects.equals(oldConfig.getCalculatedGroups(), newConfig.getCalculatedGroups());
    }

    /**
     * Processes a change event from the CRT (CRBT) system.
     * This method handles team membership changes, role changes, and team deactivations.
     *
     * @param event The deserialized Kafka message for a CRT change.
     */
    @Transactional
    public void processCrtChange(CrtChangeEvent event) {
        log.info("Processing CRT change for team '{}' ({}), member '{}'",
                event.getCrbtId(), event.getTeamType(),
                event.getMembers() != null ? event.getMembers().getEmployeeId() : "unknown");

        // Handle team deactivation
        if (event.isTeamDeactivated()) {
            processTeamDeactivation(event);
            return;
        }

        // Handle member leaving
        if (event.isMemberLeaving()) {
            processMemberLeaving(event);
            return;
        }

        // Handle member addition or role change
        processMemberChange(event);
    }

    /**
     * Process team deactivation events
     */
    private void processTeamDeactivation(CrtChangeEvent event) {
        log.info("Processing team deactivation for team {}", event.getCrbtId());

        Optional<CrbtTeam> teamOpt = crbtTeamRepository.findById(event.getCrbtId());
        if (teamOpt.isEmpty()) {
            log.warn("Team deactivation event received for non-existent team: {}", event.getCrbtId());
            return;
        }

        CrbtTeam team = teamOpt.get();
        team.setActive(false);
        crbtTeamRepository.save(team);

        // Remove all memberships for this team
        List<UserTeamMembership> memberships = userTeamMembershipRepository.findByTeamCrbtId(event.getCrbtId());
        userTeamMembershipRepository.deleteAll(memberships);

        // Recalculate configurations for all affected users
        Set<String> affectedUsers = memberships.stream()
                .map(membership -> membership.getId().getUserUsername())
                .collect(Collectors.toSet());

        recalculateAndPushUpdates(affectedUsers);
        log.info("Team {} deactivated and {} users recalculated", event.getCrbtId(), affectedUsers.size());
    }

    /**
     * Process member leaving events
     */
    private void processMemberLeaving(CrtChangeEvent event) {
        log.info("Processing member leaving for team {} member {}",
                event.getCrbtId(), event.getMembers().getEmployeeId());

        // Find the user by employee ID
        Optional<AppUser> userOpt = appUserRepository.findByEmployeeId(event.getMembers().getEmployeeId());
        if (userOpt.isEmpty()) {
            log.warn("Member leaving event received for non-existent user: {}", event.getMembers().getEmployeeId());
            return;
        }

        AppUser user = userOpt.get();
        UserTeamMembershipId membershipId = new UserTeamMembershipId(user.getUsername(), event.getCrbtId());

        Optional<UserTeamMembership> membershipOpt = userTeamMembershipRepository.findById(membershipId);
        if (membershipOpt.isPresent()) {
            userTeamMembershipRepository.delete(membershipOpt.get());

            // Recalculate and push update for this user
            AppUser updatedConfig = complianceLogicService.calculateConfigurationForUser(user.getUsername());
            vendorApiClient.updateUser(updatedConfig);

            log.info("User {} removed from team {} and configuration updated", user.getUsername(), event.getCrbtId());
        } else {
            log.warn("Member leaving event for user {} not in team {}", user.getUsername(), event.getCrbtId());
        }
    }

    /**
     * Process member addition or role change events
     */
    private void processMemberChange(CrtChangeEvent event) {
        log.info("Processing member change for team {} member {} role {}",
                event.getCrbtId(), event.getMembers().getEmployeeId(), event.getMembers().getRole());

        // Find or validate the team exists
        Optional<CrbtTeam> teamOpt = crbtTeamRepository.findById(event.getCrbtId());
        if (teamOpt.isEmpty()) {
            log.warn("Member change event received for non-existent team: {}", event.getCrbtId());
            return;
        }

        // Find the user by employee ID
        Optional<AppUser> userOpt = appUserRepository.findByEmployeeId(event.getMembers().getEmployeeId());
        if (userOpt.isEmpty()) {
            log.warn("Member change event received for non-existent user: {}", event.getMembers().getEmployeeId());
            return;
        }

        AppUser user = userOpt.get();
        CrbtTeam team = teamOpt.get();

        UserTeamMembershipId membershipId = new UserTeamMembershipId(user.getUsername(), event.getCrbtId());
        Optional<UserTeamMembership> existingMembership = userTeamMembershipRepository.findById(membershipId);

        if (existingMembership.isPresent()) {
            // Update existing membership role
            UserTeamMembership membership = existingMembership.get();
            String oldRole = membership.getMemberRole();
            membership.setMemberRole(event.getMembers().getRole());
            userTeamMembershipRepository.save(membership);

            log.info("Updated user {} role in team {} from {} to {}",
                    user.getUsername(), event.getCrbtId(), oldRole, event.getMembers().getRole());
        } else {
            // Create new membership
            UserTeamMembership newMembership = new UserTeamMembership();
            newMembership.setId(membershipId);
            newMembership.setUser(user);
            newMembership.setTeam(team);
            newMembership.setMemberRole(event.getMembers().getRole());
            userTeamMembershipRepository.save(newMembership);

            log.info("Added user {} to team {} with role {}",
                    user.getUsername(), event.getCrbtId(), event.getMembers().getRole());
        }

        // Recalculate and push update for this user
        AppUser updatedConfig = complianceLogicService.calculateConfigurationForUser(user.getUsername());
        vendorApiClient.updateUser(updatedConfig);

        // If this is a managerial change, also update any direct reports
        if (event.isManagerialChange()) {
            List<AppUser> directReports = appUserRepository.findByManagerUsername(user.getUsername());
            if (!directReports.isEmpty()) {
                log.info("Managerial change detected. Recalculating {} direct reports", directReports.size());
                Set<String> reportUsernames = directReports.stream()
                        .map(AppUser::getUsername)
                        .collect(Collectors.toSet());
                recalculateAndPushUpdates(reportUsernames);
            }
        }
    }

    /**
     * Helper method to recalculate and push updates for multiple users
     */
    private void recalculateAndPushUpdates(Set<String> usernames) {
        usernames.forEach(username -> {
            AppUser updatedConfig = complianceLogicService.calculateConfigurationForUser(username);
            vendorApiClient.updateUser(updatedConfig);
        });
    }
}
