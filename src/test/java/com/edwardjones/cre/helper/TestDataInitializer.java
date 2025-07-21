package com.edwardjones.cre.helper;

import com.edwardjones.cre.client.AdLdapClient;
import com.edwardjones.cre.client.CrbtApiClient;
import com.edwardjones.cre.model.domain.AppUser;
import com.edwardjones.cre.model.domain.CrbtTeam;
import com.edwardjones.cre.model.domain.UserTeamMembership;
import com.edwardjones.cre.model.domain.UserTeamMembershipId;
import com.edwardjones.cre.repository.AppUserRepository;
import com.edwardjones.cre.repository.CrbtTeamRepository;
import com.edwardjones.cre.repository.UserTeamMembershipRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Helper service to initialize test data in a controlled, consistent manner.
 * This ensures every test starts with a fresh, known dataset.
 */
@Service
public class TestDataInitializer {

    private static final Logger log = LoggerFactory.getLogger(TestDataInitializer.class);

    @Autowired
    private AdLdapClient adLdapClient;
    @Autowired
    private CrbtApiClient crbtApiClient;
    @Autowired
    private AppUserRepository appUserRepository;
    @Autowired
    private CrbtTeamRepository crbtTeamRepository;
    @Autowired
    private UserTeamMembershipRepository userTeamMembershipRepository;

    /**
     * Sets up the initial test state by clearing existing data and populating
     * from our mock JSON files. This mirrors the BootstrapService logic but
     * is specifically designed for testing scenarios.
     */
    @Transactional
    public void setupInitialState() {
        log.info("Setting up test data...");

        try {
            // Clear existing state in the correct order to avoid foreign key constraint violations
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

            // Load data from mock sources
            var usersFromAd = adLdapClient.fetchAllUsers();
            var teams = crbtApiClient.fetchAllTeams();

            // Stage 1: Save all users without manager links to ensure all PKs exist
            Map<String, String> managerMapping = new HashMap<>();
            Set<String> existingUsernames = new HashSet<>();

            usersFromAd.forEach(user -> {
                managerMapping.put(user.getUsername(), user.getManagerUsername());
                existingUsernames.add(user.getUsername());
                user.setManagerUsername(null); // Temporarily nullify for the first save
            });

            // Save users in batches and flush to ensure they're persisted
            appUserRepository.saveAll(usersFromAd);
            appUserRepository.flush();

            // Stage 2: Update manager relationships one by one to avoid lazy loading issues
            for (AppUser user : usersFromAd) {
                String originalManagerUsername = managerMapping.get(user.getUsername());
                if (originalManagerUsername != null && existingUsernames.contains(originalManagerUsername)) {
                    // Find the persisted user and update only the manager field
                    AppUser persistedUser = appUserRepository.findById(user.getUsername())
                            .orElseThrow(() -> new RuntimeException("User not found after save: " + user.getUsername()));
                    persistedUser.setManagerUsername(originalManagerUsername);
                    appUserRepository.save(persistedUser);
                } else if (originalManagerUsername != null) {
                    log.debug("Skipping invalid manager reference: {} -> {}", user.getUsername(), originalManagerUsername);
                }
            }
            appUserRepository.flush();

            // Save teams
            crbtTeamRepository.saveAll(teams);
            crbtTeamRepository.flush();

            // Create basic team memberships for testing (skip complex CRBT API data for now)
            createBasicTeamMemberships();

            log.info("Test data setup complete: {} users, {} teams", usersFromAd.size(), teams.size());

        } catch (Exception e) {
            log.error("Error during test data setup: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to setup test data", e);
        }
    }

    /**
     * Helper method to create a specific user for testing the Hybrid (HOBR) scenario.
     * This user will have characteristics of both Home Office and Branch.
     */
    @Transactional
    public void setupHybridUser() {
        AppUser hybridUser = new AppUser();
        hybridUser.setUsername("hobr001");
        hybridUser.setEmployeeId("999001");
        hybridUser.setFirstName("Hybrid");
        hybridUser.setLastName("User");
        hybridUser.setTitle("Branch Support Analyst"); // A branch-like title
        hybridUser.setDistinguishedName("CN=hobr001,OU=Associates,OU=Home Office,OU=US,OU=People,DC=edj,DC=ad,DC=edwardjones,DC=com"); // HO distinguished name
        hybridUser.setCountry("US");
        hybridUser.setActive(true);
        appUserRepository.save(hybridUser);
        appUserRepository.flush();
        log.info("Created hybrid user for testing: {}", hybridUser.getUsername());
    }

    /**
     * Helper method to create a user who is a member of multiple teams with different precedence (VTM > HTM > SFA).
     */
    @Transactional
    public void setupMultiTeamUser() {
        AppUser multiTeamUser = new AppUser();
        multiTeamUser.setUsername("multi01");
        multiTeamUser.setEmployeeId("999002");
        multiTeamUser.setFirstName("Multi");
        multiTeamUser.setLastName("Team");
        multiTeamUser.setTitle("Financial Advisor");
        multiTeamUser.setDistinguishedName("CN=multi01,OU=FA,OU=Branch,OU=US,OU=People,DC=edj,DC=ad,DC=edwardjones,DC=com");
        multiTeamUser.setCountry("US");
        multiTeamUser.setActive(true);
        appUserRepository.save(multiTeamUser);
        appUserRepository.flush();

        // Create teams for precedence testing
        CrbtTeam vtmTeam = createTestTeam(312595, "JOHN_FRANK/_KEVIN_FLORER", "VTM");
        CrbtTeam htmTeam = createTestTeam(316000, "TECHNOLOGY_SOLUTIONS", "HTM");
        CrbtTeam sfaTeam = createTestTeam(315000, "COMPLIANCE_OVERSIGHT", "SFA");

        // Add user to teams with different precedence
        createMembership(multiTeamUser.getUsername(), vtmTeam.getCrbtId(), "MEMBER", LocalDate.now().minusDays(10)); // VTM (highest precedence)
        createMembership(multiTeamUser.getUsername(), htmTeam.getCrbtId(), "MEMBER", LocalDate.now().minusDays(20)); // HTM (mid precedence)
        createMembership(multiTeamUser.getUsername(), sfaTeam.getCrbtId(), "MEMBER", LocalDate.now().minusDays(30)); // SFA (lowest precedence)

        log.info("Created multi-team user for testing: {} with {} team memberships",
                multiTeamUser.getUsername(), 3);
    }

    private CrbtTeam createTestTeam(Integer crbtId, String teamName, String teamType) {
        Optional<CrbtTeam> existingTeam = crbtTeamRepository.findById(crbtId);
        if (existingTeam.isPresent()) {
            return existingTeam.get();
        }

        CrbtTeam team = new CrbtTeam();
        team.setCrbtId(crbtId);
        team.setTeamName(teamName);
        team.setTeamType(teamType);
        team.setActive(true);
        crbtTeamRepository.save(team);
        crbtTeamRepository.flush();
        return team;
    }

    private UserTeamMembership createMembership(String username, Integer teamId, String role, LocalDate startDate) {
        UserTeamMembershipId membershipId = new UserTeamMembershipId(username, teamId);

        // Check if membership already exists
        Optional<UserTeamMembership> existing = userTeamMembershipRepository.findById(membershipId);
        if (existing.isPresent()) {
            return existing.get();
        }

        UserTeamMembership membership = new UserTeamMembership();
        membership.setId(membershipId);

        AppUser user = appUserRepository.findById(username)
                .orElseThrow(() -> new RuntimeException("User not found: " + username));
        CrbtTeam team = crbtTeamRepository.findById(teamId)
                .orElseThrow(() -> new RuntimeException("Team not found: " + teamId));

        membership.setUser(user);
        membership.setTeam(team);
        membership.setMemberRole(role);
        membership.setEffectiveStartDate(startDate);
        membership.setEffectiveEndDate(null);

        userTeamMembershipRepository.save(membership);
        userTeamMembershipRepository.flush();
        return membership;
    }

    /**
     * Create basic team memberships for core testing without relying on complex CRBT API data
     */
    private void createBasicTeamMemberships() {
        try {
            // Create a simple VTM team membership for j050001 (Branch Leader)
            Optional<AppUser> branchLeader = appUserRepository.findById("j050001");
            Optional<CrbtTeam> vtmTeam = crbtTeamRepository.findById(312595);

            if (branchLeader.isPresent() && vtmTeam.isPresent()) {
                UserTeamMembershipId membershipId = new UserTeamMembershipId("j050001", 312595);
                UserTeamMembership membership = new UserTeamMembership();
                membership.setId(membershipId);
                membership.setUser(branchLeader.get());
                membership.setTeam(vtmTeam.get());
                membership.setMemberRole("LEAD");
                membership.setEffectiveStartDate(LocalDate.now().minusDays(30));
                membership.setEffectiveEndDate(null);

                userTeamMembershipRepository.save(membership);
                log.info("Created basic team membership for testing: {} -> team {}", "j050001", 312595);
            }
        } catch (Exception e) {
            log.warn("Could not create basic team memberships: {}", e.getMessage());
        }
    }
}
