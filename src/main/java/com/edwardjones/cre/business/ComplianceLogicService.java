package com.edwardjones.cre.business;

import com.edwardjones.cre.model.domain.AppUser;
import com.edwardjones.cre.model.domain.CrbtTeam;
import com.edwardjones.cre.model.domain.UserTeamMembership;
import com.edwardjones.cre.repository.AppUserRepository;
import com.edwardjones.cre.repository.UserTeamMembershipRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The core business logic engine of the compliance synchronization service.
 * This class is responsible for translating the complex rules from the original
 * PowerShell scripts into Java. It reads the current state from the database
 * repositories and calculates the "desired" configuration (Groups and Visibility Profile)
 * for a given user.
 */
@Service
@RequiredArgsConstructor
public class ComplianceLogicService {

    private final AppUserRepository appUserRepository;
    private final UserTeamMembershipRepository userTeamMembershipRepository;

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
     * Main public method to calculate the complete desired configuration for a user.
     * This orchestrates all the private business rule methods.
     *
     * @param username The PJNumber of the user to process.
     * @return An AppUser object with the transient fields 'calculatedGroups' and
     *         'calculatedVisibilityProfile' populated with the correct values.
     */
    public AppUser calculateConfigurationForUser(String username) {
        AppUser user = appUserRepository.findById(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found in state DB: " + username));

        // Determine the user's classification
        UserType userType = determineUserType(user);

        CalculatedConfiguration finalConfig;

        // Special logic for users in multiple FA teams (VTM/HTM/SFA) takes precedence
        if (user.getTeamMemberships() != null && user.getTeamMemberships().size() > 1) {
            finalConfig = generateConfigurationFromMultipleGroups(user);
        } else {
            // Standard logic based on user type (HO, BR, Leader, etc.)
            finalConfig = generateConfigurationForUserType(user, userType);
        }

        // Populate the transient fields on the user entity to return the result
        user.setCalculatedGroups(finalConfig.groups());
        user.setCalculatedVisibilityProfile(finalConfig.visibilityProfileName());

        return user;
    }

    /**
     * Overloaded method that accepts an AppUser directly (for backward compatibility)
     */
    public AppUser calculateConfigurationForUser(AppUser user) {
        return calculateConfigurationForUser(user.getUsername());
    }

    /**
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
     * Corresponds to the PowerShell function: get-visibilityProfile
     * This is the main dispatcher for calculating configuration.
     */
    private CalculatedConfiguration generateConfigurationForUserType(AppUser user, UserType userType) {
        // Use a switch expression for cleaner code
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

        // A standard user belongs to the base submitter group for their type
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
                 // Mimic set-BranchGroupsVisProfile logic
                 String baseGroupName = String.format("%s-%s", leader.getCountry(), "State-Placeholder"); // Simplified

                 finalGroupNames = memberships.stream()
                     .map(m -> String.format("%s-%s-%s-%s",
                         baseGroupName,
                         m.getTeam().getTeamName(),
                         "FA-No-Placeholder", // FA No not in our state model, can be added if needed
                         m.getTeam().getTeamType()
                     ).replace(" ", "_").replace(".", ""))
                     .collect(Collectors.toSet());

                 // VP name is derived from the generated group names (using the last one for simplicity)
                 finalVpName = "Vis_" + finalGroupNames.stream().reduce((first, second) -> second).orElse("");
             }

             finalGroupNames.add(SUBMITTER_GROUPS.get(leader.getCountry() + "-BR"));
             return new CalculatedConfiguration(finalVpName, finalGroupNames);
        }

        return new CalculatedConfiguration("Error-Profile", Collections.emptySet());
    }

    /**
     * Corresponds to the PowerShell function: get-visibilityProfileFromGroups
     * This handles the complex case of a user (often a BOA) in multiple teams.
     */
    private CalculatedConfiguration generateConfigurationFromMultipleGroups(AppUser user) {
        // This is a direct translation of the VTM > HTM > SFA precedence logic
        Map<String, CrbtTeam> finalTeams = new HashMap<>(); // CRBT ID -> Team
        Set<String> fasCovered = new HashSet<>();

        List<UserTeamMembership> memberships = userTeamMembershipRepository.findByUserUsername(user.getUsername());

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
                    // Check if this team covers anyone not already covered by a higher-tier team
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
     * A simple record to hold the results of a calculation, ensuring the
     * visibility profile name and the set of associated groups are always returned together.
     */
    private record CalculatedConfiguration(String visibilityProfileName, Set<String> groups) {}
}
