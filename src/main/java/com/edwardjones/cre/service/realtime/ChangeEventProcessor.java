package com.edwardjones.cre.service.realtime;

import com.edwardjones.cre.service.logic.ComplianceLogicService;
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

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Lean orchestrator for processing Kafka change events.
 *
 * Responsibilities (Single Responsibility Principle):
 * 1. Receive and validate Kafka events
 * 2. Update state in the H2 database
 * 3. Determine scope of impact (which users are affected)
 * 4. Delegate business logic and vendor updates to ComplianceLogicService
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

    /**
     * Main entry point for AD change events.
     * Orchestrates: State Update -> Impact Analysis -> Business Logic Delegation
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
     * Orchestrates: State Update -> Impact Analysis -> Business Logic Delegation
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

        Optional<AppUser> existingUser = appUserRepository.findById(event.getPjNumber());
        if (existingUser.isPresent()) {
            log.warn("⚠️ NewUser event received for existing user: {}", event.getPjNumber());
            return;
        }

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

        // 2. Delegate to business logic service
        complianceLogicService.recalculateAndPushUpdate(user);

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
        Set<AppUser> affectedUsers = new HashSet<>();
        affectedUsers.add(user); // The user themselves is always affected

        // If this is an impactful change to a leader, their direct reports are also affected
        if (isImpactfulChange) {
            List<AppUser> directReports = appUserRepository.findByManagerUsername(user.getUsername());
            if (!directReports.isEmpty()) {
                log.info("👥 Leader change detected. Adding {} direct reports to impact scope", directReports.size());
                affectedUsers.addAll(directReports);
            }
        }

        // 3. Delegate to business logic service
        complianceLogicService.recalculateAndPushUpdates(affectedUsers);

        log.info("✅ Processed AD change for {} users", affectedUsers.size());
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
        userTeamMembershipRepository.deleteAll(memberships);

        // 2. Determine impact scope
        Set<AppUser> affectedUsers = memberships.stream()
                .map(membership -> membership.getUser())
                .collect(Collectors.toSet());

        // 3. Delegate to business logic service
        complianceLogicService.recalculateAndPushUpdates(affectedUsers);

        log.info("✅ Team {} deactivated, {} users recalculated", event.getCrbtId(), affectedUsers.size());
    }

    private void processMemberLeaving(CrtChangeEvent event) {
        log.info("👋 Processing member leaving: team={}, member={}",
                event.getCrbtId(), event.getMembers().getEmployeeId());

        Optional<AppUser> userOpt = appUserRepository.findByEmployeeId(event.getMembers().getEmployeeId());
        if (userOpt.isEmpty()) {
            log.warn("⚠️ Member leaving event received for non-existent user: {}", event.getMembers().getEmployeeId());
            return;
        }

        // 1. Update state
        AppUser user = userOpt.get();
        UserTeamMembershipId membershipId = new UserTeamMembershipId(user.getUsername(), event.getCrbtId());

        Optional<UserTeamMembership> membershipOpt = userTeamMembershipRepository.findById(membershipId);
        if (membershipOpt.isPresent()) {
            userTeamMembershipRepository.delete(membershipOpt.get());

            // 2. Delegate to business logic service
            complianceLogicService.recalculateAndPushUpdate(user);

            log.info("✅ User {} removed from team {} and recalculated", user.getUsername(), event.getCrbtId());
        } else {
            log.warn("⚠️ Member leaving event for user {} not in team {}", user.getUsername(), event.getCrbtId());
        }
    }

    private void processMemberChange(CrtChangeEvent event) {
        log.info("🔄 Processing member change: team={}, member={}, role={}",
                event.getCrbtId(), event.getMembers().getEmployeeId(), event.getMembers().getRole());

        // Validate team exists
        Optional<CrbtTeam> teamOpt = crbtTeamRepository.findById(event.getCrbtId());
        if (teamOpt.isEmpty()) {
            log.warn("⚠️ Member change event received for non-existent team: {}", event.getCrbtId());
            return;
        }

        // Validate user exists
        Optional<AppUser> userOpt = appUserRepository.findByEmployeeId(event.getMembers().getEmployeeId());
        if (userOpt.isEmpty()) {
            log.warn("⚠️ Member change event received for non-existent user: {}", event.getMembers().getEmployeeId());
            return;
        }

        // 1. Update state
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
        Set<AppUser> affectedUsers = new HashSet<>();
        affectedUsers.add(user);

        // If this is a managerial change, also include direct reports
        if (event.isManagerialChange()) {
            List<AppUser> directReports = appUserRepository.findByManagerUsername(user.getUsername());
            if (!directReports.isEmpty()) {
                log.info("👥 Managerial change detected. Adding {} direct reports to impact scope", directReports.size());
                affectedUsers.addAll(directReports);
            }
        }

        // 3. Delegate to business logic service
        complianceLogicService.recalculateAndPushUpdates(affectedUsers);

        log.info("✅ Processed member change for {} users", affectedUsers.size());
    }

    // ==================== UTILITY METHODS ====================

    /**
     * Apply AD changes to user entity. Returns true if the change could impact direct reports.
     */
    private boolean applyAdChangeToUser(AppUser user, AdChangeEvent event) {
        switch (event.getProperty().toLowerCase()) {
            case "manager":
                user.setManagerUsername(parsePjFromDn(event.getNewValue()));
                return false; // Changing a user's manager doesn't impact their reports
            case "title":
                user.setTitle(event.getNewValue());
                return true; // A leader's title change can affect group names
            case "distinguishedname":
                user.setDistinguishedName(event.getNewValue());
                return true; // A leader's OU change could affect configurations
            case "enabled":
                user.setActive("true".equalsIgnoreCase(event.getNewValue()));
                return false;
            case "ej-irnumber":
                user.setEmployeeId(event.getNewValue());
                return false;
            case "state":
                user.setCountry(event.getNewValue());
                return true; // Location changes could affect compliance groups
            case "name":
                log.info("📛 Name change detected for user {}: {} -> {}",
                        user.getUsername(), event.getBeforeValue(), event.getNewValue());
                return false;
            default:
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
