package com.edwardjones.cre.service.bootstrap;

import com.edwardjones.cre.client.AdLdapClient;
import com.edwardjones.cre.client.CrbtApiClient;
import com.edwardjones.cre.model.domain.AppUser;
import com.edwardjones.cre.model.domain.CrbtTeam;
import com.edwardjones.cre.model.domain.UserTeamMembership;
import com.edwardjones.cre.model.domain.UserTeamMembershipId;
import com.edwardjones.cre.repository.AppUserRepository;
import com.edwardjones.cre.repository.CrbtTeamRepository;
import com.edwardjones.cre.repository.UserTeamMembershipRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class BootstrapService {
    private final AdLdapClient adLdapClient;
    private final CrbtApiClient crbtApiClient;
    private final AppUserRepository appUserRepository;
    private final CrbtTeamRepository crbtTeamRepository;
    private final UserTeamMembershipRepository userTeamMembershipRepository;
    private final Environment environment;

    @PostConstruct
    @Transactional
    public void initializeState() {
        // Bootstrap will now always run when the application starts, including during tests
        performBootstrap();
    }

    /**
     * Public method to allow manual bootstrap triggering during tests.
     * This enables tests to control when bootstrap happens with mock data.
     */
    @Transactional
    public void performBootstrap() {
        log.info("--- STARTING BOOTSTRAP PROCESS ---");

        // Clear old state
        userTeamMembershipRepository.deleteAll();

        // First, set all manager references to null to break circular dependencies
        appUserRepository.findAll().forEach(user -> {
            user.setManagerUsername(null);
            appUserRepository.save(user);
        });
        appUserRepository.flush();

        // Now we can safely delete all users
        appUserRepository.deleteAll();
        crbtTeamRepository.deleteAll();

        // Fetch from sources (will use mock implementations in test profile)
        var usersFromAd = adLdapClient.fetchAllUsers();
        var teams = crbtApiClient.fetchAllTeams();

        // --- FIX: Two-stage user saving to avoid foreign key violations ---
        // Stage 1: Save all users without ANY manager references
        Map<String, String> managerMapping = new HashMap<>();
        Set<String> existingUsernames = new HashSet<>();

        usersFromAd.forEach(user -> {
            managerMapping.put(user.getUsername(), user.getManagerUsername()); // Save the mapping
            existingUsernames.add(user.getUsername()); // Track all valid usernames
            user.setManagerUsername(null); // Clear FK field
            user.setManager(null); // Clear entity reference
            // Also clear any collections that might cause issues
            if (user.getDirectReports() != null) {
                user.getDirectReports().clear();
            }
            if (user.getTeamMemberships() != null) {
                user.getTeamMemberships().clear();
            }
        });

        // Save each user individually to avoid batch processing issues
        for (AppUser user : usersFromAd) {
            appUserRepository.save(user);
        }
        appUserRepository.flush(); // Force the insert to the DB

        // Stage 2: Re-apply the manager links one by one to avoid lazy loading issues
        for (String username : existingUsernames) {
            String originalManagerUsername = managerMapping.get(username);
            if (originalManagerUsername != null && existingUsernames.contains(originalManagerUsername)) {
                // Update only the manager field, not the whole entity
                AppUser user = appUserRepository.findById(username).orElseThrow();
                user.setManagerUsername(originalManagerUsername);
                appUserRepository.save(user);
            } else if (originalManagerUsername != null) {
                log.warn("Skipping invalid manager reference: {} -> {}", username, originalManagerUsername);
            }
        }
        appUserRepository.flush();

        // Save teams (no foreign key dependencies)
        crbtTeamRepository.saveAll(teams);

        log.info("Bootstrap: Saved {} users and {} teams", usersFromAd.size(), teams.size());

        // Now fetch teams with member details and create memberships
        createTeamMembershipsFromCrbtApi();

        log.info("--- BOOTSTRAP PROCESS COMPLETE ---");
    }

    /**
     * Create team memberships using the new CRBT API format with memberList
     */
    private void createTeamMembershipsFromCrbtApi() {
        log.info("Creating team memberships from CRBT API data...");

        var teamsWithMembers = crbtApiClient.fetchAllTeamsWithMembers();
        int membershipCount = 0;

        for (CrbtApiClient.CrbtApiTeamResponse teamResponse : teamsWithMembers) {
            // Ensure the team exists in our database
            Optional<CrbtTeam> teamOpt = crbtTeamRepository.findById(teamResponse.crbtID);
            if (teamOpt.isEmpty()) {
                log.warn("Team {} not found in database during membership creation", teamResponse.crbtID);
                continue;
            }

            CrbtTeam team = teamOpt.get();

            // Create memberships for each member in the team
            for (CrbtApiClient.CrbtApiTeamResponse.CrbtApiMember member : teamResponse.memberList) {
                // Find the user by their p/j number (mbrJorP)
                Optional<AppUser> userOpt = appUserRepository.findById(member.mbrJorP);
                if (userOpt.isEmpty()) {
                    log.warn("User {} not found in database for team membership", member.mbrJorP);
                    continue;
                }

                AppUser user = userOpt.get();

                // Create the membership
                UserTeamMembershipId membershipId = new UserTeamMembershipId(user.getUsername(), team.getCrbtId());
                UserTeamMembership membership = new UserTeamMembership();
                membership.setId(membershipId);
                membership.setUser(user);
                membership.setTeam(team);
                membership.setMemberRole(member.mbrRoleCd);
                membership.setEffectiveStartDate(LocalDate.now().minusDays(30)); // Assume active for 30 days
                membership.setEffectiveEndDate(null); // Active membership

                userTeamMembershipRepository.save(membership);
                membershipCount++;

                log.debug("Created membership: {} in team {} as {}",
                    user.getUsername(), team.getCrbtId(), member.mbrRoleCd);
            }
        }

        log.info("Bootstrap: Created {} team memberships from CRBT API data", membershipCount);

        // Log some interesting findings for testing
        logBootstrapSummary(teamsWithMembers);
    }

    /**
     * Log interesting findings from the bootstrap process for testing validation
     */
    private void logBootstrapSummary(java.util.List<CrbtApiClient.CrbtApiTeamResponse> teamsWithMembers) {
        log.info("=== BOOTSTRAP SUMMARY ===");

        // Find users with multiple team memberships
        for (CrbtApiClient.CrbtApiTeamResponse team : teamsWithMembers) {
            for (CrbtApiClient.CrbtApiTeamResponse.CrbtApiMember member : team.memberList) {
                long membershipCount = teamsWithMembers.stream()
                    .mapToLong(t -> t.memberList.stream()
                        .filter(m -> m.mbrJorP.equals(member.mbrJorP))
                        .count())
                    .sum();

                if (membershipCount > 1) {
                    log.info("Multi-team user found: {} is in {} teams (tests precedence logic)",
                        member.mbrJorP, membershipCount);
                }
            }
        }

        // Log team type distribution
        log.info("Team types loaded: {}",
            teamsWithMembers.stream()
                .map(t -> t.teamTyCd)
                .distinct()
                .toList());

        log.info("=== END BOOTSTRAP SUMMARY ===");
    }
}
