package com.edwardjones.cre.service.logic;

import com.edwardjones.cre.model.domain.AppUser;
import com.edwardjones.cre.model.domain.CrbtTeam;
import com.edwardjones.cre.model.domain.UserTeamMembership;
import com.edwardjones.cre.model.dto.DesiredConfiguration;
import com.edwardjones.cre.repository.AppUserRepository;
import com.edwardjones.cre.repository.UserTeamMembershipRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Pure business logic service for compliance calculations.
 *
 * Responsibilities:
 * - Calculate desired user configurations based on business rules
 * - Return immutable DesiredConfiguration DTOs
 * - NO knowledge of vendor APIs or Kafka processing
 *
 * This service contains the single source of truth for all compliance logic.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ComplianceLogicService {

    private final AppUserRepository appUserRepository;
    private final UserTeamMembershipRepository userTeamMembershipRepository;

    // Translation of business rules to submitter groups
    private static final Map<String, String> SUBMITTER_GROUPS = Map.of(
            "CA-HO", "CAN Home Office Submitters",
            "US-HO", "US Home Office Submitters",
            "CA-BR", "CAN Field Submitters",
            "US-BR", "US Field Submitters"
    );

    // UserType enum for classification
    private enum UserType {
        NOT_SPECIFIED, HO, HOBR, HO_LEADER, BR, BR_TEAM
    }

    /**
     * The single, authoritative method for calculating a user's desired configuration.
     * It reads from the state database and applies all business rules.
     */
    @Transactional(readOnly = true)
    public DesiredConfiguration calculateConfigurationForUser(String username) {
        AppUser user = appUserRepository.findById(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));

        log.debug("Calculating configuration for user: {} ({})", user.getUsername(), user.getTitle());

        // Determine the user's classification
        UserType userType = determineUserType(user);
        log.debug("User {} classified as: {}", user.getUsername(), userType);

        CalculatedResult finalConfig;

        // Special logic for users in multiple FA teams (VTM/HTM/SFA) takes precedence
        List<UserTeamMembership> memberships = userTeamMembershipRepository.findByUserUsername(user.getUsername());
        if (memberships != null && memberships.size() > 1) {
            log.debug("User {} has multiple team memberships, applying precedence logic", user.getUsername());
            finalConfig = generateConfigurationFromMultipleGroups(user, memberships);
        } else {
            // Standard logic based on user type (HO, BR, Leader, etc.)
            finalConfig = generateConfigurationForUserType(user, userType);
        }

        log.debug("Final configuration for {}: VP={}, Groups={}",
                 user.getUsername(), finalConfig.visibilityProfileName(), finalConfig.groups());

        return new DesiredConfiguration(
            user.getUsername(),
            user.getFirstName(),
            user.getLastName(),
            finalConfig.visibilityProfileName(),
            finalConfig.groups(),
            user.isActive()
        );
    }

    /**
     * Determines the user classification based on their AD attributes.
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
     */
    private CalculatedResult generateConfigurationForUserType(AppUser user, UserType userType) {
        return switch (userType) {
            case HO -> generateStandardProfile(user.getCountry(), "HO", "Home Office Regular User");
            case BR -> generateStandardProfile(user.getCountry(), "BR", "Branch Regular User - No Leader");
            case HOBR -> generateStandardProfile(user.getCountry(), "HO-BR", "Home Office Branch Support User");
            case HO_LEADER, BR_TEAM -> generateLeaderProfile(user, userType);
            default -> new CalculatedResult("Default-Profile", Collections.emptySet());
        };
    }

    /**
     * Handles the logic for simple, non-leader profiles (HO, BR, HOBR).
     */
    private CalculatedResult generateStandardProfile(String country, String type, String description) {
        String profileKey = country + "-" + type;
        String vpName = "Vis-" + profileKey;

        Set<String> groups = new HashSet<>();
        if(SUBMITTER_GROUPS.containsKey(profileKey)){
             groups.add(SUBMITTER_GROUPS.get(profileKey));
        } else if (type.equals("HO-BR")) { // HOBR is a combination
             groups.add(SUBMITTER_GROUPS.get(country + "-HO"));
             groups.add(SUBMITTER_GROUPS.get(country + "-BR"));
        }

        return new CalculatedResult(vpName, groups);
    }

    /**
     * Handles leader profile generation.
     */
    private CalculatedResult generateLeaderProfile(AppUser leader, UserType userType) {
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

            return new CalculatedResult(vpName, groups);
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
             return new CalculatedResult(finalVpName, finalGroupNames);
        }

        return new CalculatedResult("Error-Profile", Collections.emptySet());
    }

    /**
     * Handles the complex case of a user in multiple teams (VTM > HTM > SFA precedence).
     */
    private CalculatedResult generateConfigurationFromMultipleGroups(AppUser user, List<UserTeamMembership> memberships) {
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

        return new CalculatedResult(vpName, finalGroups);
    }

    /**
     * Helper to generate a group name for a leader's direct report.
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
    private record CalculatedResult(String visibilityProfileName, Set<String> groups) {}
}
