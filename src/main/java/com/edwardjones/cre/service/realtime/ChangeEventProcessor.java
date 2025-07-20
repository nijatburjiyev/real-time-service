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
        log.info("Processing AD change for user '{}', property '{}'", event.getPjNumber(), event.getProperty());

        AppUser user = appUserRepository.findById(event.getPjNumber())
                .orElseThrow(() -> new IllegalStateException("Received AD change event for user not in state DB: " + event.getPjNumber()));

        // --- 1. Capture Old State & Identify Impact Scope ---
        // We must calculate the old configuration BEFORE we apply any changes to the user entity.
        AppUser oldConfigUser = complianceLogicService.calculateConfigurationForUser(user.getUsername());
        Set<String> affectedUsernames = new HashSet<>();
        affectedUsernames.add(user.getUsername()); // The user themselves is always affected.

        // --- 2. Apply the Change to the Local State Database ---
        boolean isImpactfulChange = applyAdChangeToUser(user, event);
        appUserRepository.save(user);

        // --- 3. Perform Impact Analysis ---
        // If the change could affect a leader's team (e.g., title change), we must re-process all their reports.
        if (isImpactfulChange) {
            List<AppUser> directReports = appUserRepository.findByManagerUsername(user.getUsername());
            if (directReports != null && !directReports.isEmpty()) {
                log.info("Change to leader {} is impactful. Queueing {} direct reports for reprocessing.", user.getUsername(), directReports.size());
                directReports.forEach(report -> affectedUsernames.add(report.getUsername()));
            }
        }

        // --- 4. Recalculate and Push Updates for All Affected Users ---
        log.info("Recalculating and pushing updates for {} affected users.", affectedUsernames.size());
        affectedUsernames.forEach(username -> {
            // We compare the primary user's config, but always push updates for downstream users
            if (username.equals(user.getUsername())) {
                AppUser newConfigUser = complianceLogicService.calculateConfigurationForUser(username);
                if (!configurationChanged(oldConfigUser, newConfigUser)) {
                    log.info("Configuration for user {} has changed. Pushing update.", username);
                    vendorApiClient.updateUser(newConfigUser);
                } else {
                    log.info("Configuration for user {} did not change. No update needed.", username);
                }
            } else {
                // For downstream users (direct reports), recalculate and push unconditionally
                // as their config may have changed due to their leader's update.
                AppUser reportConfig = complianceLogicService.calculateConfigurationForUser(username);
                vendorApiClient.updateUser(reportConfig);
            }
        });
    }

    /**
     * Processes a change event from the CRBT team system.
     * This is more complex as a team change can affect many users.
     *
     * @param event The deserialized Kafka message for a CRT change.
     */
    @Transactional
    public void processCrtChange(CrtChangeEvent event) {
        log.info("Processing CRBT change for team ID '{}'", event.getCrbtId());

        CrbtTeam team = crbtTeamRepository.findById(event.getCrbtId()).orElse(new CrbtTeam());
        team.setCrbtId(event.getCrbtId());
        team.setTeamType(event.getTeamType());
        team.setActive(event.getEffectiveEndDate() == null);
        crbtTeamRepository.save(team);

        // --- 1. Identify All Users Associated with the Team (Past and Present) ---
        // This is crucial to find users who may have been removed.
        Set<String> affectedUsernames = userTeamMembershipRepository.findByTeamCrbtId(team.getCrbtId())
                .stream()
                .map(membership -> membership.getId().getUserUsername())
                .collect(Collectors.toSet());

        // --- 2. Update Team Memberships in the State Database ---
        if (event.getMembers() != null) {
            CrtChangeEvent.CrtMemberChange memberChange = event.getMembers();
            Optional<AppUser> memberUserOpt = appUserRepository.findByEmployeeId(memberChange.getEmployeeId());
            if (memberUserOpt.isEmpty()) {
                log.warn("Could not find user for employeeId {} from CRT event. Skipping membership update.", memberChange.getEmployeeId());
            } else {
                AppUser memberUser = memberUserOpt.get();
                // Add this user to the affected list, as they are part of the current event
                affectedUsernames.add(memberUser.getUsername());

                UserTeamMembershipId id = new UserTeamMembershipId(memberUser.getUsername(), team.getCrbtId());

                UserTeamMembership membership = userTeamMembershipRepository.findById(id).orElse(new UserTeamMembership());
                membership.setId(id);
                membership.setUser(memberUser);
                membership.setTeam(team);
                membership.setMemberRole(memberChange.getRole());
                membership.setEffectiveEndDate(memberChange.getMemberEffectiveEndDate());
                membership.setEffectiveStartDate(memberChange.getMemberEffectiveBeginDate());

                userTeamMembershipRepository.save(membership);
            }
        }

        // --- 3. Recalculate and Push for All Affected Users ---
        // A team change can alter the Group/VP for every member.
        log.info("CRBT change requires reprocessing for {} users associated with team {}.", affectedUsernames.size(), team.getCrbtId());

        // You might also need to push updates for the Group/VP entities themselves first
        // vendorApiClient.updateGroup( ... );
        // vendorApiClient.updateVisibilityProfile( ... );

        affectedUsernames.forEach(username -> {
            AppUser newConfig = complianceLogicService.calculateConfigurationForUser(username);
            vendorApiClient.updateUser(newConfig);
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
     * Helper method to compare two user configurations to detect changes.
     */
    private boolean configurationChanged(AppUser oldConfig, AppUser newConfig) {
        return !Objects.equals(oldConfig.getCalculatedGroups(), newConfig.getCalculatedGroups()) ||
               !Objects.equals(oldConfig.getCalculatedVisibilityProfile(), newConfig.getCalculatedVisibilityProfile());
    }
}
