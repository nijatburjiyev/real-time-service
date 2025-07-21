package com.edwardjones.cre.service.logic;

import com.edwardjones.cre.client.VendorApiClient;
import com.edwardjones.cre.client.AdLdapClient;
import com.edwardjones.cre.client.CrbtApiClient;
import com.edwardjones.cre.model.domain.AppUser;
import com.edwardjones.cre.model.domain.CrbtTeam;
import com.edwardjones.cre.model.domain.UserTeamMembership;
import com.edwardjones.cre.model.dto.CrbtTeamWithMembers;
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
 * Production-ready implementation that follows the PowerShell three-phase workflow:
 * Phase 1: Process all leaders first (config-Leader equivalent)
 * Phase 2: Process all users using pre-calculated manager data
 * Phase 3: Process branch teams via CRT API calls
 *
 * This ensures proper dependency resolution and data consistency.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ComplianceLogicService {

    private final AppUserRepository appUserRepository;
    private final UserTeamMembershipRepository userTeamMembershipRepository;
    private final VendorApiClient vendorApiClient;

    // Placeholders for external integrations
    private final AdLdapClient adLdapClient; // TODO: Implement real AD integration
    private final CrbtApiClient crbtApiClient; // TODO: Implement real CRT API integration

    // Global state management (equivalent to PowerShell's global hashtables)
    private final Map<String, ManagerConfiguration> managersCache = new HashMap<>();
    private final Map<String, Set<String>> userGroupsCache = new HashMap<>();
    private final Map<String, String> userVisibilityCache = new HashMap<>();

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

    /**
     * ENHANCED PRIMARY API - Full bulk recalculation with proper dependency resolution.
     * This implements the three-phase PowerShell workflow for production use.
     */
    @Transactional
    public void recalculateAllUsersWithDependencyResolution() {
        log.info("=== STARTING FULL COMPLIANCE RECALCULATION (3-PHASE PROCESS) ===");

        clearCaches();

        try {
            // Phase 1: Process all leaders first (equivalent to config-Leader)
            processAllLeadersPhase();

            // Phase 2: Process all users using pre-calculated manager data
            processAllUsersPhase();

            // Phase 3: Process branch teams via CRT API calls
            processBranchTeamsPhase();

            // Phase 4: Push all calculated configurations to vendor
            pushAllCalculatedConfigurationsToVendor();

            log.info("=== FULL COMPLIANCE RECALCULATION COMPLETED SUCCESSFULLY ===");

        } catch (Exception e) {
            log.error("❌ Critical error during bulk recalculation", e);
            clearCaches(); // Clean up on failure
            throw new RuntimeException("Bulk recalculation failed", e);
        }
    }

    /**
     * PHASE 1: Process all leaders first (equivalent to PowerShell config-Leader function)
     * This pre-calculates all manager configurations and stores them in managersCache.
     */
    private void processAllLeadersPhase() {
        log.info("🔄 PHASE 1: Processing all leaders...");

        // Find all users who have direct reports (i.e., are managers/leaders)
        List<AppUser> allLeaders = findAllLeaders();
        log.info("Found {} leaders to process", allLeaders.size());

        for (AppUser leader : allLeaders) {
            try {
                ManagerConfiguration managerConfig = processLeader(leader);
                managersCache.put(leader.getUsername(), managerConfig);
                log.debug("✅ Processed leader: {} ({})", leader.getUsername(), leader.getTitle());
            } catch (Exception e) {
                log.error("❌ Error processing leader {}: {}", leader.getUsername(), e.getMessage(), e);
                // Continue with other leaders even if one fails
            }
        }

        log.info("✅ PHASE 1 COMPLETE: Processed {} leaders", managersCache.size());
    }

    /**
     * PHASE 2: Process all users using pre-calculated manager data
     * This is equivalent to the main user processing loop in PowerShell.
     */
    private void processAllUsersPhase() {
        log.info("🔄 PHASE 2: Processing all users...");

        List<AppUser> allUsers = appUserRepository.findAll();
        log.info("Found {} users to process", allUsers.size());

        int processedCount = 0;
        for (AppUser user : allUsers) {
            try {
                CalculatedConfiguration config = calculateConfigurationForUserWithCache(user);

                // Store in caches for later vendor push
                userGroupsCache.put(user.getUsername(), config.groups());
                userVisibilityCache.put(user.getUsername(), config.visibilityProfileName());

                processedCount++;

                if (processedCount % 100 == 0) {
                    log.debug("Processed {} users so far...", processedCount);
                }

            } catch (Exception e) {
                log.error("❌ Error processing user {}: {}", user.getUsername(), e.getMessage(), e);
                // Continue with other users even if one fails
            }
        }

        log.info("✅ PHASE 2 COMPLETE: Processed {} users", processedCount);
    }

    /**
     * PHASE 3: Process branch teams via CRT API calls
     * This handles the complex team-based configurations for branch users.
     */
    private void processBranchTeamsPhase() {
        log.info("🔄 PHASE 3: Processing branch teams...");

        // Get all branch leaders who own teams
        Set<String> branchLeadersWithTeams = managersCache.values().stream()
                .filter(manager -> !manager.isHomeOffice() && manager.getTeamIds() != null)
                .map(ManagerConfiguration::getUsername)
                .collect(Collectors.toSet());

        log.info("Found {} branch leaders with teams to process", branchLeadersWithTeams.size());

        for (String leaderUsername : branchLeadersWithTeams) {
            try {
                processBranchLeaderTeams(leaderUsername);
                log.debug("✅ Processed teams for branch leader: {}", leaderUsername);
            } catch (Exception e) {
                log.error("❌ Error processing teams for leader {}: {}", leaderUsername, e.getMessage(), e);
                // Continue with other leaders even if one fails
            }
        }

        log.info("✅ PHASE 3 COMPLETE: Processed branch teams");
    }

    /**
     * PHASE 4: Push all calculated configurations to vendor
     */
    private void pushAllCalculatedConfigurationsToVendor() {
        log.info("🔄 PHASE 4: Pushing all configurations to vendor...");

        int successCount = 0;
        int errorCount = 0;

        for (Map.Entry<String, Set<String>> entry : userGroupsCache.entrySet()) {
            String username = entry.getKey();
            Set<String> groups = entry.getValue();
            String visibilityProfile = userVisibilityCache.get(username);

            try {
                AppUser user = appUserRepository.findById(username).orElse(null);
                if (user != null) {
                    user.setCalculatedGroups(groups);
                    user.setCalculatedVisibilityProfile(visibilityProfile);

                    vendorApiClient.updateUser(user);
                    successCount++;
                }
            } catch (Exception e) {
                log.error("❌ Error pushing user {} to vendor: {}", username, e.getMessage(), e);
                errorCount++;
            }
        }

        log.info("✅ PHASE 4 COMPLETE: {} successful, {} errors", successCount, errorCount);
    }

    /**
     * Process a single leader (equivalent to PowerShell config-Leader function)
     */
    private ManagerConfiguration processLeader(AppUser leader) {
        log.debug("Processing leader: {} ({})", leader.getUsername(), leader.getTitle());

        ManagerConfiguration config = new ManagerConfiguration();
        config.setUsername(leader.getUsername());
        config.setDepartment("Compliance"); // TODO: Extract from AD data

        // Determine leader type
        boolean isHomeOffice = isHomeOfficeUser(leader);
        boolean isBranch = isBranchUser(leader);
        config.setHomeOffice(isHomeOffice);
        config.setBranch(isBranch);

        // Get direct reports and analyze their locations
        List<AppUser> directReports = appUserRepository.findByManagerUsername(leader.getUsername());
        Map<String, Integer> countryCount = new HashMap<>();

        for (AppUser report : directReports) {
            String country = report.getCountry();
            countryCount.merge(country, 1, Integer::sum);
        }
        config.setCountries(countryCount);

        if (isHomeOffice) {
            // Home Office leader logic
            processHomeOfficeLeader(leader, config, countryCount);
        } else if (isBranch) {
            // Branch leader logic - includes CRT API calls
            processBranchLeader(leader, config);
        }

        return config;
    }

    /**
     * Process Home Office leader (creates personalized groups and VPs)
     */
    private void processHomeOfficeLeader(AppUser leader, ManagerConfiguration config, Map<String, Integer> countryCount) {
        Map<String, String> groupsByCountry = new HashMap<>();

        for (String country : countryCount.keySet()) {
            // Create group name: US_HO_Katherine_Powell_(p100001)
            String groupName = String.format("%s_HO_%s_%s_(%s)",
                    country,
                    leader.getFirstName(),
                    leader.getLastName(),
                    leader.getUsername())
                    .replace(" ", "_")
                    .replace(".", "");

            groupsByCountry.put(country, groupName);
        }

        config.setGroups(groupsByCountry);

        // Create visibility profiles
        String vpForReports = String.format("Vis_%s_HO_%s_%s_(%s)",
                leader.getCountry(),
                leader.getFirstName(),
                leader.getLastName(),
                leader.getUsername())
                .replace(" ", "_")
                .replace(".", "");

        String vpForSelf = String.format("Vis_HO_%s_%s_(%s)",
                leader.getFirstName(),
                leader.getLastName(),
                leader.getUsername())
                .replace(" ", "_")
                .replace(".", "");

        config.setVisibilityProfileName(vpForReports);
        config.setVisibilityProfileNameSelf(vpForSelf);
    }

    /**
     * Process Branch leader (includes CRT API integration)
     */
    private void processBranchLeader(AppUser leader, ManagerConfiguration config) {
        // TODO: Implement CRT API call to get teams owned by this leader
        // Placeholder for now
        Set<String> teamIds = getCrtTeamsOwnedByUser(leader.getUsername());
        config.setTeamIds(teamIds);

        // Branch leaders get team-based configurations
        if (!teamIds.isEmpty()) {
            // TODO: Create team-based group and VP names
            String defaultVp = String.format("Vis_BR_Team_%s", leader.getUsername());
            config.setVisibilityProfileNameSelf(defaultVp);
        }
    }

    /**
     * Process branch leader teams (equivalent to PowerShell config-FATeam)
     */
    private void processBranchLeaderTeams(String leaderUsername) {
        ManagerConfiguration managerConfig = managersCache.get(leaderUsername);
        if (managerConfig == null || managerConfig.getTeamIds() == null) {
            return;
        }

        for (String teamId : managerConfig.getTeamIds()) {
            try {
                // TODO: Call CRT API to get full team member list
                CrbtTeamWithMembers teamData = getCrtTeamWithMembers(teamId);

                if (teamData != null) {
                    processTeamMembers(teamData, leaderUsername);
                }
            } catch (Exception e) {
                log.error("Error processing team {}: {}", teamId, e.getMessage(), e);
            }
        }
    }

    /**
     * Process team members and update their configurations
     */
    private void processTeamMembers(CrbtTeamWithMembers teamData, String leaderUsername) {
        String teamGroupName = String.format("%s-%s-%s",
                teamData.getTeamType(),
                teamData.getTeamName().replace(" ", "_"),
                leaderUsername);

        String teamVpName = "Vis_" + teamGroupName;

        for (String memberUsername : teamData.getMemberUsernames()) {
            // Update the cached configurations for team members
            Set<String> existingGroups = userGroupsCache.getOrDefault(memberUsername, new HashSet<>());
            existingGroups.add(teamGroupName);
            userGroupsCache.put(memberUsername, existingGroups);

            // Team members get the team-based visibility profile
            userVisibilityCache.put(memberUsername, teamVpName);
        }
    }

    /**
     * Calculate configuration for a user using pre-calculated manager data
     */
    private CalculatedConfiguration calculateConfigurationForUserWithCache(AppUser user) {
        Set<String> groups = new HashSet<>();
        String visibilityProfile;

        // Check if user has a manager with pre-calculated configuration
        if (user.getManagerUsername() != null && managersCache.containsKey(user.getManagerUsername())) {
            ManagerConfiguration managerConfig = managersCache.get(user.getManagerUsername());

            // Get the group for this user's country
            String userCountry = user.getCountry();
            if (managerConfig.getGroups().containsKey(userCountry)) {
                groups.add(managerConfig.getGroups().get(userCountry));
            }

            // Get the visibility profile from manager
            visibilityProfile = managerConfig.getVisibilityProfileName();

        } else if (managersCache.containsKey(user.getUsername())) {
            // This user is a leader themselves
            ManagerConfiguration managerConfig = managersCache.get(user.getUsername());

            // Leaders are members of their own groups
            groups.addAll(managerConfig.getGroups().values());

            // Leaders use their "self" visibility profile
            visibilityProfile = managerConfig.getVisibilityProfileNameSelf();

        } else {
            // Standard user without a pre-calculated manager
            UserType userType = determineUserType(user);
            CalculatedConfiguration standardConfig = generateConfigurationForUserType(user, userType);
            groups.addAll(standardConfig.groups());
            visibilityProfile = standardConfig.visibilityProfileName();
        }

        // Add generic submitter groups based on user type and location
        addGenericSubmitterGroups(user, groups);

        return new CalculatedConfiguration(visibilityProfile, groups);
    }

    // === PLACEHOLDER METHODS FOR EXTERNAL INTEGRATIONS ===

    /**
     * TODO: Implement real CRT API call to get teams owned by a user
     */
    private Set<String> getCrtTeamsOwnedByUser(String username) {
        // Placeholder - in production, this would call the real CRT API
        log.debug("PLACEHOLDER: Getting CRT teams owned by user: {}", username);
        return new HashSet<>();
    }

    /**
     * TODO: Implement real CRT API call to get team with full member list
     */
    private CrbtTeamWithMembers getCrtTeamWithMembers(String teamId) {
        // Placeholder - in production, this would call the real CRT API
        log.debug("PLACEHOLDER: Getting CRT team with members: {}", teamId);
        return null;
    }

    /**
     * TODO: Implement real AD API call to refresh user data
     */
    private void refreshUserDataFromAd(AppUser user) {
        // Placeholder - in production, this would call the real AD API
        log.debug("PLACEHOLDER: Refreshing AD data for user: {}", user.getUsername());
    }

    // === UTILITY METHODS ===

    private List<AppUser> findAllLeaders() {
        // Find users who have direct reports
        return appUserRepository.findAll().stream()
                .filter(user -> !appUserRepository.findByManagerUsername(user.getUsername()).isEmpty())
                .collect(Collectors.toList());
    }

    private void clearCaches() {
        managersCache.clear();
        userGroupsCache.clear();
        userVisibilityCache.clear();
    }

    private void addGenericSubmitterGroups(AppUser user, Set<String> groups) {
        String userType = isHomeOfficeUser(user) ? "HO" : "BR";
        String profileKey = user.getCountry() + "-" + userType;

        if (SUBMITTER_GROUPS.containsKey(profileKey)) {
            groups.add(SUBMITTER_GROUPS.get(profileKey));
        }
    }

    private boolean isHomeOfficeUser(AppUser user) {
        return user.getDistinguishedName() != null &&
               user.getDistinguishedName().contains("OU=Home Office");
    }

    private boolean isBranchUser(AppUser user) {
        String dn = user.getDistinguishedName();
        String title = user.getTitle() != null ? user.getTitle() : "";

        return (dn != null && dn.contains("OU=Branch")) ||
               title.matches("(?i).*Branch.*|.*Remote Support.*|.*On-Caller.*");
    }
}
