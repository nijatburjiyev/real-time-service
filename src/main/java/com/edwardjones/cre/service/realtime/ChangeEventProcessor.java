package com.edwardjones.cre.service.realtime;

import com.edwardjones.cre.client.VendorApiClient;
import com.edwardjones.cre.model.domain.AppUser;
import com.edwardjones.cre.model.domain.CrbtTeam;
import com.edwardjones.cre.model.domain.UserTeamMembership;
import com.edwardjones.cre.model.domain.UserTeamMembershipId;
import com.edwardjones.cre.model.dto.AdChangeEvent;
import com.edwardjones.cre.model.dto.CrtChangeEvent;
import com.edwardjones.cre.model.dto.DesiredConfiguration;
import com.edwardjones.cre.repository.AppUserRepository;
import com.edwardjones.cre.repository.CrbtTeamRepository;
import com.edwardjones.cre.repository.UserTeamMembershipRepository;
import com.edwardjones.cre.service.logic.ComplianceLogicService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Clean orchestrator for processing Kafka change events.
 *
 * Responsibilities (Single Responsibility Principle):
 * 1. Receive and validate Kafka events
 * 2. Update state in the H2 database
 * 3. Determine scope of impact (which users are affected)
 * 4. Delegate business logic to ComplianceLogicService
 * 5. Push calculated results to vendor via VendorApiClient
 *
 * This class should NOT contain complex business logic - that belongs in ComplianceLogicService.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChangeEventProcessor {

    private static final Pattern PJ_NUMBER_PATTERN = Pattern.compile("CN=((p|j)\\d{5,6}),", Pattern.CASE_INSENSITIVE);

    // Data access
    private final AppUserRepository appUserRepository;
    private final CrbtTeamRepository crbtTeamRepository;
    private final UserTeamMembershipRepository userTeamMembershipRepository;

    // Business logic delegation
    private final ComplianceLogicService complianceLogicService;

    // Vendor integration
    private final VendorApiClient vendorApiClient;

    /**
     * Main entry point for AD change events.
     * Orchestrates: State Update -> Impact Analysis -> Business Logic Delegation -> Vendor Push
     */
    @Transactional
    public void processAdChange(AdChangeEvent event) {
        log.info("🔄 Processing AD change: user='{}', type='{}', property='{}'",
                event.getPjNumber(), event.getChangeType(), event.getProperty());

        // Handle different change types with specific flows
        if (event.isNewUser()) {
            processNewUserEvent(event);
            return;
        }

        if (event.isTerminatedUser()) {
            processTerminatedUserEvent(event);
            return;
        }

        if (event.isDataChange()) {
            processDataChangeEvent(event);
            return;
        }

        log.warn("⚠️ Unknown AD change type '{}' for user '{}'", event.getChangeType(), event.getPjNumber());
    }

    /**
     * Main entry point for CRT change events.
     * Orchestrates: State Update -> Impact Analysis -> Business Logic Delegation -> Vendor Push
     */
    @Transactional
    public void processCrtChange(CrtChangeEvent event) {
        log.info("🔄 Processing CRT change: team='{}' ({}), member='{}'",
                event.getCrbtId(), event.getTeamType(),
                event.getMembers() != null ? event.getMembers().getEmployeeId() : "unknown");

        if (event.isTeamDeactivated()) {
            processTeamDeactivation(event);
            return;
        }

        if (event.isMemberLeaving()) {
            processMemberLeaving(event);
            return;
        }

        // Member addition or role change
        processMemberChange(event);
    }

    // ==================== AD EVENT HANDLERS ====================

    private void processNewUserEvent(AdChangeEvent event) {
        log.info("📝 Processing new user creation: {}", event.getPjNumber());
        // TODO: Implement full user profile fetch and creation logic
        log.info("ℹ️ New user {} requires full profile fetch from AD", event.getPjNumber());
    }

    private void processTerminatedUserEvent(AdChangeEvent event) {
        log.info("🚫 Processing user termination: {}", event.getPjNumber());

        Optional<AppUser> userOpt = appUserRepository.findById(event.getPjNumber());
        if (userOpt.isEmpty()) {
            log.warn("⚠️ TerminatedUser event received for non-existent user: {}", event.getPjNumber());
            return;
        }

        // 1. Update state
        AppUser user = userOpt.get();
        user.setActive(false);
        appUserRepository.save(user);

        // 2. Delegate to business logic service and push to vendor
        processAndPushUserUpdate(user.getUsername());

        log.info("✅ User {} deactivated and vendor updated", event.getPjNumber());
    }

    private void processDataChangeEvent(AdChangeEvent event) {
        log.info("📊 Processing data change: user='{}', property='{}'", event.getPjNumber(), event.getProperty());

        Optional<AppUser> userOpt = appUserRepository.findById(event.getPjNumber());
        if (userOpt.isEmpty()) {
            log.warn("⚠️ DataChange event received for non-existent user: {}", event.getPjNumber());
            return;
        }

        AppUser user = userOpt.get();

        // 1. Apply the change to local state
        boolean isImpactfulChange = applyAdChangeToUser(user, event);
        if (!isImpactfulChange) {
            log.info("ℹ️ DataChange for user '{}' property '{}' is not impactful", user.getUsername(), event.getProperty());
            return;
        }

        appUserRepository.save(user);

        // 2. Determine scope of impact
        Set<String> affectedUsernames = new HashSet<>();
        affectedUsernames.add(user.getUsername()); // The user themselves is always affected

        // If this is an impactful change to a leader, their direct reports are also affected
        List<AppUser> directReports = appUserRepository.findByManagerUsername(user.getUsername());
        if (!directReports.isEmpty()) {
            log.info("👥 Leader change detected. Adding {} direct reports to impact scope", directReports.size());
            directReports.forEach(report -> affectedUsernames.add(report.getUsername()));
        }

        // 3. Delegate to business logic service and push to vendor
        processAndPushUserUpdates(affectedUsernames);

        log.info("✅ Processed AD change for {} users", affectedUsernames.size());
    }

    // ==================== CRT EVENT HANDLERS ====================

    private void processTeamDeactivation(CrtChangeEvent event) {
        log.info("🚫 Processing team deactivation: {}", event.getCrbtId());

        Optional<CrbtTeam> teamOpt = crbtTeamRepository.findById(event.getCrbtId());
        if (teamOpt.isEmpty()) {
            log.warn("⚠️ Team deactivation event received for non-existent team: {}", event.getCrbtId());
            return;
        }

        // 1. Update state
        CrbtTeam team = teamOpt.get();
        team.setActive(false);
        crbtTeamRepository.save(team);

        List<UserTeamMembership> memberships = userTeamMembershipRepository.findByTeamCrbtId(event.getCrbtId());
        Set<String> affectedUsernames = memberships.stream()
                .map(membership -> membership.getUser().getUsername())
                .collect(Collectors.toSet());

        userTeamMembershipRepository.deleteAll(memberships);

        // 2. Delegate to business logic service and push to vendor
        processAndPushUserUpdates(affectedUsernames);

        log.info("✅ Team {} deactivated, {} users recalculated", event.getCrbtId(), affectedUsernames.size());
    }

    private void processMemberLeaving(CrtChangeEvent event) {
        log.info("👋 Processing member leaving: team={}, member={}",
                event.getCrbtId(), event.getMembers().getEmployeeId());

        Optional<AppUser> userOpt = appUserRepository.findByEmployeeId(event.getMembers().getEmployeeId());
        if (userOpt.isEmpty()) {
            log.warn("⚠️ Member leaving event received for non-existent user: {}", event.getMembers().getEmployeeId());
            return;
        }

        AppUser user = userOpt.get();
        UserTeamMembershipId membershipId = new UserTeamMembershipId(user.getUsername(), event.getCrbtId());

        Optional<UserTeamMembership> membershipOpt = userTeamMembershipRepository.findById(membershipId);
        if (membershipOpt.isPresent()) {
            userTeamMembershipRepository.delete(membershipOpt.get());
            processAndPushUserUpdate(user.getUsername());
            log.info("✅ User {} removed from team {} and recalculated", user.getUsername(), event.getCrbtId());
        } else {
            log.warn("⚠️ Member leaving event for user {} not in team {}", user.getUsername(), event.getCrbtId());
        }
    }

    private void processMemberChange(CrtChangeEvent event) {
        log.info("🔄 Processing member change: team={}, member={}, role={}",
                event.getCrbtId(), event.getMembers().getEmployeeId(), event.getMembers().getRole());

        // Validate team and user exist
        Optional<CrbtTeam> teamOpt = crbtTeamRepository.findById(event.getCrbtId());
        Optional<AppUser> userOpt = appUserRepository.findByEmployeeId(event.getMembers().getEmployeeId());

        if (teamOpt.isEmpty() || userOpt.isEmpty()) {
            log.warn("⚠️ Member change event received for non-existent team {} or user {}",
                    event.getCrbtId(), event.getMembers().getEmployeeId());
            return;
        }

        AppUser user = userOpt.get();
        CrbtTeam team = teamOpt.get();

        // 1. Update state
        UserTeamMembershipId membershipId = new UserTeamMembershipId(user.getUsername(), event.getCrbtId());
        Optional<UserTeamMembership> existingMembership = userTeamMembershipRepository.findById(membershipId);

        if (existingMembership.isPresent()) {
            // Update existing membership role
            UserTeamMembership membership = existingMembership.get();
            String oldRole = membership.getMemberRole();
            membership.setMemberRole(event.getMembers().getRole());
            userTeamMembershipRepository.save(membership);
            log.info("📝 Updated user {} role in team {} from {} to {}",
                    user.getUsername(), event.getCrbtId(), oldRole, event.getMembers().getRole());
        } else {
            // Create new membership
            UserTeamMembership newMembership = new UserTeamMembership();
            newMembership.setId(membershipId);
            newMembership.setUser(user);
            newMembership.setTeam(team);
            newMembership.setMemberRole(event.getMembers().getRole());
            userTeamMembershipRepository.save(newMembership);
            log.info("➕ Added user {} to team {} with role {}",
                    user.getUsername(), event.getCrbtId(), event.getMembers().getRole());
        }

        // 2. Determine impact scope
        Set<String> affectedUsernames = new HashSet<>();
        affectedUsernames.add(user.getUsername());

        // If this is a managerial change, also include direct reports
        if (event.isManagerialChange()) {
            List<AppUser> directReports = appUserRepository.findByManagerUsername(user.getUsername());
            if (!directReports.isEmpty()) {
                log.info("👥 Managerial change detected. Adding {} direct reports to impact scope", directReports.size());
                directReports.forEach(report -> affectedUsernames.add(report.getUsername()));
            }
        }

        // 3. Delegate to business logic service and push to vendor
        processAndPushUserUpdates(affectedUsernames);

        log.info("✅ Processed member change for {} users", affectedUsernames.size());
    }

    // ==================== UTILITY METHODS ====================

    /**
     * Process a single user through the business logic service and push to vendor
     */
    private void processAndPushUserUpdate(String username) {
        try {
            DesiredConfiguration config = complianceLogicService.calculateConfigurationForUser(username);
            vendorApiClient.updateUser(config);
            log.debug("✅ Successfully processed and pushed user: {}", username);
        } catch (Exception e) {
            log.error("❌ Error processing user {}: {}", username, e.getMessage(), e);
        }
    }

    /**
     * Process multiple users through the business logic service and push to vendor.
     * Enhanced with resilience - if one user fails, others in the batch continue processing.
     */
    private void processAndPushUserUpdates(Set<String> usernames) {
        if (usernames == null || usernames.isEmpty()) {
            return;
        }
        log.info("[KAFKA-REALTIME] Recalculating and pushing updates for {} affected users.", usernames.size());

        int successCount = 0;
        int failureCount = 0;

        for (String username : usernames) {
            try {
                DesiredConfiguration config = complianceLogicService.calculateConfigurationForUser(username);
                vendorApiClient.updateUser(config);
                log.debug("✅ Successfully processed and pushed user: {}", username);
                successCount++;
            } catch (Exception e) {
                // This is a critical log. It tells you that a specific user failed to update
                // while allowing the rest of the batch to proceed.
                log.error("[KAFKA-REALTIME][REALTIME_FAILURE] Failed to process update for user '{}'. This user may be out of sync until the next reconciliation run.", username, e);
                failureCount++;
                // Do NOT re-throw the exception here - continue processing other users
            }
        }

        log.info("[KAFKA-REALTIME] Batch processing complete: {} successful, {} failed", successCount, failureCount);
    }

    /**
     * Apply the AD change to the user's local state.
     * @return true if this change could impact the user's calculated configuration
     */
    private boolean applyAdChangeToUser(AppUser user, AdChangeEvent event) {
        log.debug("📝 Applying AD change to user {}: {} = {}", user.getUsername(), event.getProperty(), event.getNewValue());

        String propertyLower = event.getProperty().toLowerCase();

        if (AdChangeEvent.PROPERTY_MANAGER.toLowerCase().equals(propertyLower) ||
            AdChangeEvent.PROPERTY_MANAGER_USERNAME.toLowerCase().equals(propertyLower)) {
            String newManagerUsername = event.getNewValue();
            if (newManagerUsername != null && newManagerUsername.startsWith("CN=")) {
                newManagerUsername = parsePjFromDn(newManagerUsername);
            }
            user.setManagerUsername(newManagerUsername);
            return true;

        } else if (AdChangeEvent.PROPERTY_TITLE.toLowerCase().equals(propertyLower)) {
            user.setTitle(event.getNewValue());
            return true;

        } else if (AdChangeEvent.PROPERTY_DISTINGUISHED_NAME.toLowerCase().equals(propertyLower)) {
            user.setDistinguishedName(event.getNewValue());
            return true;

        } else if (AdChangeEvent.PROPERTY_ENABLED.toLowerCase().equals(propertyLower)) {
            user.setActive("true".equalsIgnoreCase(event.getNewValue()));
            return false; // Enable/disable changes are not considered impactful for calculations

        } else if (AdChangeEvent.PROPERTY_EJ_IR_NUMBER.toLowerCase().equals(propertyLower)) {
            user.setEmployeeId(event.getNewValue());
            return false;

        } else if (AdChangeEvent.PROPERTY_STATE.toLowerCase().equals(propertyLower)) {
            user.setCountry(event.getNewValue());
            return true;

        } else {
            log.warn("⚠️ Unhandled AD property change for user {}: {}", user.getUsername(), event.getProperty());
            return false;
        }
    }

    /**
     * Parse PJ Number from Distinguished Name.
     */
    private String parsePjFromDn(String dn) {
        if (dn == null || dn.isEmpty()) {
            return null;
        }
        Matcher matcher = PJ_NUMBER_PATTERN.matcher(dn);
        if (matcher.find()) {
            return matcher.group(1);
        }
        log.warn("⚠️ Could not parse PJ Number from DN: {}", dn);
        return null;
    }
}
