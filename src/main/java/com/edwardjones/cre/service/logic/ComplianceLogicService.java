package com.edwardjones.cre.service.logic;

import com.edwardjones.cre.client.VendorApiClient;
import com.edwardjones.cre.model.domain.AppUser;
import com.edwardjones.cre.model.domain.CrbtTeam;
import com.edwardjones.cre.model.domain.UserTeamMembership;
import com.edwardjones.cre.repository.AppUserRepository;
import com.edwardjones.cre.repository.UserTeamMembershipRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The central brain of the compliance synchronization service.
 * This is the single source of truth for all business logic and the primary
 * orchestrator for configuration calculations and vendor updates.
 *
 * Responsibilities:
 * 1. Calculate desired configurations for users based on business rules
 * 2. Orchestrate bulk recalculation and updates
 * 3. Serve as the single point of integration with the vendor API
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ComplianceLogicService {

    private final AppUserRepository appUserRepository;
    private final UserTeamMembershipRepository userTeamMembershipRepository;
    private final VendorApiClient vendorApiClient;

    // Translation of PowerShell's $submitterGroups hashtable
    private static final Map<String, String> SUBMITTER_GROUPS = Map.of(
            "CA-HO", "CAN Home Office Submitters",
            "US-HO", "US Home Office Submitters",
            "CA-BR", "CAN Field Submitters",
            "US-BR", "US Field Submitters"
    );

    // UserType enum to replace PowerShell enum
    private enum UserType {
        NOT_SPECIFIED, HO, HOBR, HO_LEADER, BR, BR_TEAM
    }

    /**
     * PRIMARY PUBLIC API - Called by event processors and reconciliation services.
     * This is the main entry point for triggering configuration updates.
     *
     * @param users Set of users whose configurations need to be recalculated and pushed
     */
    public void recalculateAndPushUpdates(Set<AppUser> users) {
        log.info("=== COMPLIANCE LOGIC SERVICE: Processing {} users ===", users.size());

        int updateCount = 0;
        int errorCount = 0;

        for (AppUser user : users) {
            try {
                // 1. Calculate the desired configuration
                AppUser calculatedUser = calculateConfigurationForUser(user);

                // 2. Push to vendor
                vendorApiClient.updateUser(calculatedUser);
                updateCount++;

                log.debug("✅ Successfully processed user: {}", user.getUsername());

            } catch (Exception e) {
                errorCount++;
                log.error("❌ Error processing user {}: {}", user.getUsername(), e.getMessage(), e);
                // Continue with other users even if one fails
            }
        }

        log.info("=== PROCESSING COMPLETE: {} successful, {} errors ===", updateCount, errorCount);
    }

    /**
     * Convenience method for single user updates.
     * Wraps the main API for single-user scenarios.
     */
    public void recalculateAndPushUpdate(AppUser user) {
        recalculateAndPushUpdates(Set.of(user));
    }

    /**
     * Calculate configuration for a user by username (for external callers like TestController).
     * This method fetches the user from the database and calculates their configuration.
     */
    public AppUser calculateConfigurationForUser(String username) {
        AppUser user = appUserRepository.findById(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found in state DB: " + username));
        return calculateConfigurationForUser(user);
    }

    /**
     * Main configuration calculation method.
     * This orchestrates all the private business rule methods.
     *
     * @param user The user entity to process
     * @return An AppUser object with calculated fields populated
     */
    public AppUser calculateConfigurationForUser(AppUser user) {
        log.debug("Calculating configuration for user: {} ({})", user.getUsername(), user.getTitle());

        // Determine the user's classification
        UserType userType = determineUserType(user);
        log.debug("User {} classified as: {}", user.getUsername(), userType);

        CalculatedConfiguration finalConfig;

        // Special logic for users in multiple FA teams (VTM/HTM/SFA) takes precedence
        List<UserTeamMembership> memberships = userTeamMembershipRepository.findByUserUsername(user.getUsername());
        if (memberships != null && memberships.size() > 1) {
            log.debug("User {} has multiple team memberships, applying precedence logic", user.getUsername());
            finalConfig = generateConfigurationFromMultipleGroups(user, memberships);
        } else {
            // Standard logic based on user type (HO, BR, Leader, etc.)
            finalConfig = generateConfigurationForUserType(user, userType);
        }

        // Populate the transient fields on the user entity to return the result
        user.setCalculatedGroups(finalConfig.groups());
        user.setCalculatedVisibilityProfile(finalConfig.visibilityProfileName());

        log.debug("Final configuration for {}: VP={}, Groups={}",
                 user.getUsername(), finalConfig.visibilityProfileName(), finalConfig.groups());

        return user;
    }

    /**
     * Determines the user classification based on their AD attributes.
     * Corresponds to the PowerShell function: get-userType
     */
    private UserType determineUserType(AppUser user) {
        boolean isLeader = !appUserRepository.findByManagerUsername(user.getUsername()).isEmpty();

        String dn = user.getDistinguishedName();
        String title = user.getTitle() != null ? user.getTitle() : "";

        boolean isHomeOffice = dn != null && dn.contains("OU=Home Office");
        boolean isBranch = (dn != null && dn.contains("OU=Branch")) ||
                           title.matches("(?i).*Branch.*|.*Remote Support.*|.*On-Caller.*");

        if (isLeader) {
            return isHomeOffice ? UserType.HO_LEADER : UserType.BR_TEAM;
        } else {
            if (isHomeOffice && isBranch) return UserType.HOBR;
            if (isHomeOffice) return UserType.HO;
            if (isBranch) return UserType.BR;
        }
        return UserType.NOT_SPECIFIED;
    }

    /**
     * Main dispatcher for calculating configuration based on user type.
     * Corresponds to the PowerShell function: get-visibilityProfile
     */
    private CalculatedConfiguration generateConfigurationForUserType(AppUser user, UserType userType) {
        return switch (userType) {
            case HO -> generateStandardProfile(user.getCountry(), "HO", "Home Office Regular User");
            case BR -> generateStandardProfile(user.getCountry(), "BR", "Branch Regular User - No Leader");
            case HOBR -> generateStandardProfile(user.getCountry(), "HO-BR", "Home Office Branch Support User");
            case HO_LEADER, BR_TEAM -> generateLeaderProfile(user, userType);
            default -> new CalculatedConfiguration("Default-Profile", Collections.emptySet());
        };
    }

    /**
     * Handles the logic for simple, non-leader profiles (HO, BR, HOBR).
     */
    private CalculatedConfiguration generateStandardProfile(String country, String type, String description) {
        String profileKey = country + "-" + type;
        String vpName = "Vis-" + profileKey;

        Set<String> groups = new HashSet<>();
        if(SUBMITTER_GROUPS.containsKey(profileKey)){
             groups.add(SUBMITTER_GROUPS.get(profileKey));
        } else if (type.equals("HO-BR")) { // HOBR is a combination
             groups.add(SUBMITTER_GROUPS.get(country + "-HO"));
             groups.add(SUBMITTER_GROUPS.get(country + "-BR"));
        }

        return new CalculatedConfiguration(vpName, groups);
    }

    /**
     * Handles leader profile generation.
     * Corresponds to the PowerShell logic in: config-Leader and set-BranchGroupsVisProfile
     */
    private CalculatedConfiguration generateLeaderProfile(AppUser leader, UserType userType) {
        // For Home Office leaders, the VP name is based on their own identity.
        if (userType == UserType.HO_LEADER) {
            String vpName = String.format("Vis_HO_%s_%s_(%s)",
                    leader.getFirstName(), leader.getLastName(), leader.getUsername())
                    .replace(" ", "_").replace(".", "");

            // A leader's groups are derived from their direct reports' locations and types
            Set<String> groups = appUserRepository.findByManagerUsername(leader.getUsername()).stream()
                    .map(report -> getGroupNameForReport(leader, report))
                    .collect(Collectors.toSet());

            groups.add(SUBMITTER_GROUPS.get(leader.getCountry() + "-HO"));

            return new CalculatedConfiguration(vpName, groups);
        }

        // For Branch leaders (BRTeam), the configuration is driven by their CRBT teams.
        if (userType == UserType.BR_TEAM) {
             Set<String> finalGroupNames = new HashSet<>();
             String finalVpName = "Vis_Branch_Default"; // Default if no teams

             List<UserTeamMembership> memberships = userTeamMembershipRepository.findByUserUsername(leader.getUsername());
             if(!memberships.isEmpty()){
                 String baseGroupName = String.format("%s-%s", leader.getCountry(), "State-Placeholder");

                 finalGroupNames = memberships.stream()
                     .map(m -> String.format("%s-%s-%s-%s",
                         baseGroupName,
                         m.getTeam().getTeamName(),
                         "FA-No-Placeholder",
                         m.getTeam().getTeamType()
                     ).replace(" ", "_").replace(".", ""))
                     .collect(Collectors.toSet());

                 finalVpName = "Vis_" + finalGroupNames.stream().reduce((first, second) -> second).orElse("");
             }

             finalGroupNames.add(SUBMITTER_GROUPS.get(leader.getCountry() + "-BR"));
             return new CalculatedConfiguration(finalVpName, finalGroupNames);
        }

        return new CalculatedConfiguration("Error-Profile", Collections.emptySet());
    }

    /**
     * Handles the complex case of a user in multiple teams (VTM > HTM > SFA precedence).
     * Corresponds to the PowerShell function: get-visibilityProfileFromGroups
     */
    private CalculatedConfiguration generateConfigurationFromMultipleGroups(AppUser user, List<UserTeamMembership> memberships) {
        log.debug("Processing multiple team memberships for user: {}", user.getUsername());

        Map<String, CrbtTeam> finalTeams = new HashMap<>(); // CRBT ID -> Team
        Set<String> fasCovered = new HashSet<>();

        // Process VTM teams first to establish baseline coverage
        memberships.stream()
            .filter(m -> "VTM".equals(m.getTeam().getTeamType()))
            .forEach(vtmMembership -> {
                finalTeams.put(String.valueOf(vtmMembership.getTeam().getCrbtId()), vtmMembership.getTeam());
                // Find all members of this VTM team to mark them as 'covered'
                userTeamMembershipRepository.findByTeamCrbtId(vtmMembership.getTeam().getCrbtId()).stream()
                    .map(member -> member.getUser().getUsername())
                    .forEach(fasCovered::add);
            });

        // Process HTM and SFA teams, adding them only if they cover new FAs
        Stream.of("HTM", "SFA").forEach(teamType -> {
             memberships.stream()
                .filter(m -> teamType.equals(m.getTeam().getTeamType()))
                .forEach(membership -> {
                    boolean coversNewFa = userTeamMembershipRepository.findByTeamCrbtId(membership.getTeam().getCrbtId()).stream()
                            .anyMatch(member -> !fasCovered.contains(member.getUser().getUsername()));

                    if (coversNewFa) {
                        finalTeams.put(String.valueOf(membership.getTeam().getCrbtId()), membership.getTeam());
                    }
                });
        });

        // Construct dynamic names based on the final consolidated list of teams
        String groupName = finalTeams.values().stream()
                .map(CrbtTeam::getTeamName)
                .sorted()
                .collect(Collectors.joining("_"))
                .replace(" ", "_").replace(".", "");

        String vpName = "Vis_" + groupName;

        Set<String> finalGroups = new HashSet<>();
        finalGroups.add(groupName); // The primary dynamic group
        finalGroups.add(SUBMITTER_GROUPS.get(user.getCountry() + "-BR")); // The base branch group

        return new CalculatedConfiguration(vpName, finalGroups);
    }

    /**
     * Helper to generate a group name for a leader's direct report.
     * Corresponds to PowerShell: set-groupName
     */
    private String getGroupNameForReport(AppUser manager, AppUser report) {
        String reportType = (report.getDistinguishedName() != null && report.getDistinguishedName().contains("OU=Home Office"))
                            ? "HO" : "BR";

        return String.format("%s_%s_%s_%s_(%s)",
                report.getCountry(), reportType, manager.getFirstName(), manager.getLastName(), manager.getUsername())
                .replace(" ", "_").replace(".", "");
    }

    /**
     * Configuration result record - ensures VP name and groups are always returned together.
     */
    private record CalculatedConfiguration(String visibilityProfileName, Set<String> groups) {}
}
