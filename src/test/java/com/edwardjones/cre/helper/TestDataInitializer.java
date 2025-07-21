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
            // Clear existing state
            userTeamMembershipRepository.deleteAll();
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

            // Stage 2: Re-apply valid manager links
            usersFromAd.forEach(user -> {
                String originalManagerUsername = managerMapping.get(user.getUsername());
                if (originalManagerUsername != null && existingUsernames.contains(originalManagerUsername)) {
                    user.setManagerUsername(originalManagerUsername);
                } else if (originalManagerUsername != null) {
                    log.debug("Skipping invalid manager reference: {} -> {}", user.getUsername(), originalManagerUsername);
                    user.setManagerUsername(null);
                }
            });

            // Update users with manager relationships
            appUserRepository.saveAll(usersFromAd);
            appUserRepository.flush();

            // Save teams
            crbtTeamRepository.saveAll(teams);
            crbtTeamRepository.flush();

            // Create team memberships
            createTeamMembershipsFromCrbtApi();

            log.info("Test data setup complete: {} users, {} teams", usersFromAd.size(), teams.size());

        } catch (Exception e) {
            log.error("Error during test data setup: {}", e.getMessage());
            throw new RuntimeException("Failed to setup test data", e);
        }
    }

    private void createTeamMembershipsFromCrbtApi() {
        var teamsWithMembers = crbtApiClient.fetchAllTeamsWithMembers();
        int membershipCount = 0;

        for (CrbtApiClient.CrbtApiTeamResponse teamResponse : teamsWithMembers) {
            Optional<CrbtTeam> teamOpt = crbtTeamRepository.findById(teamResponse.crbtID);
            if (teamOpt.isEmpty()) {
                log.warn("Team {} not found in database during membership creation", teamResponse.crbtID);
                continue;
            }

            CrbtTeam team = teamOpt.get();

            for (CrbtApiClient.CrbtApiTeamResponse.CrbtApiMember member : teamResponse.memberList) {
                Optional<AppUser> userOpt = appUserRepository.findById(member.mbrJorP);
                if (userOpt.isEmpty()) {
                    log.warn("User {} not found in database for team membership", member.mbrJorP);
                    continue;
                }

                AppUser user = userOpt.get();

                UserTeamMembershipId membershipId = new UserTeamMembershipId(user.getUsername(), team.getCrbtId());
                UserTeamMembership membership = new UserTeamMembership();
                membership.setId(membershipId);
                membership.setUser(user);
                membership.setTeam(team);
                membership.setMemberRole(member.mbrRoleCd);
                membership.setEffectiveStartDate(LocalDate.now().minusDays(30));
                membership.setEffectiveEndDate(null);

                userTeamMembershipRepository.save(membership);
                membershipCount++;
            }
        }

        log.info("Created {} team memberships from CRBT API data", membershipCount);
    }
}
