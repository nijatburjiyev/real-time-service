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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Clean data loading service using the new client interfaces.
 * Loads initial data from external sources into the H2 state database.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataLoadingService implements CommandLineRunner {

    private final AdLdapClient adLdapClient;
    private final CrbtApiClient crbtApiClient;

    private final AppUserRepository appUserRepository;
    private final CrbtTeamRepository crbtTeamRepository;
    private final UserTeamMembershipRepository userTeamMembershipRepository;

    @Override
    public void run(String... args) throws Exception {
        log.info("🚀 Starting application - checking if initial data load is needed...");

        // Only load data if database is empty (first startup)
        long userCount = appUserRepository.count();
        if (userCount == 0) {
            log.info("📊 Database is empty, loading initial data...");
            loadInitialData();
        } else {
            log.info("📊 Database contains {} users, skipping initial data load", userCount);
        }
    }

    /**
     * Main method to load all initial data from external sources
     */
    @Transactional
    public void loadInitialData() {
        log.info("🔄 Starting initial data loading process...");

        try {
            // 1. Load users from AD/LDAP
            loadUsersFromAd();

            // 2. Load teams from CRBT API
            loadTeamsFromCrbt();

            // 3. Load team memberships from CRBT API
            loadTeamMembershipsFromCrbt();

            log.info("✅ Initial data loading completed successfully");
            logDataLoadingSummary();

        } catch (Exception e) {
            log.error("❌ Error during initial data loading", e);
            throw new RuntimeException("Initial data loading failed", e);
        }
    }

    /**
     * Load users from AD/LDAP using the client interface
     */
    private void loadUsersFromAd() {
        log.info("👥 Loading users from AD/LDAP...");

        List<AppUser> adUsers = adLdapClient.fetchAllUsers();
        log.info("📥 Retrieved {} users from AD/LDAP", adUsers.size());

        int savedCount = 0;
        for (AppUser user : adUsers) {
            try {
                // Check if user already exists
                Optional<AppUser> existing = appUserRepository.findById(user.getUsername());
                if (existing.isPresent()) {
                    // Update existing user with latest AD data
                    AppUser existingUser = existing.get();
                    updateUserFromAdData(existingUser, user);
                    appUserRepository.save(existingUser);
                } else {
                    // Save new user
                    appUserRepository.save(user);
                }
                savedCount++;
            } catch (Exception e) {
                log.error("❌ Error saving user {}: {}", user.getUsername(), e.getMessage());
            }
        }

        log.info("✅ Processed {} users from AD/LDAP, {} saved/updated", adUsers.size(), savedCount);
    }

    /**
     * Load teams from CRBT API using the client interface
     */
    private void loadTeamsFromCrbt() {
        log.info("🏢 Loading teams from CRBT API...");

        List<CrbtTeam> crbtTeams = crbtApiClient.fetchAllTeams();
        log.info("📥 Retrieved {} teams from CRBT API", crbtTeams.size());

        int savedCount = 0;
        for (CrbtTeam team : crbtTeams) {
            try {
                // Check if team already exists
                Optional<CrbtTeam> existing = crbtTeamRepository.findById(team.getCrbtId());
                if (existing.isPresent()) {
                    // Update existing team
                    CrbtTeam existingTeam = existing.get();
                    existingTeam.setTeamName(team.getTeamName());
                    existingTeam.setTeamType(team.getTeamType());
                    existingTeam.setActive(team.isActive());
                    crbtTeamRepository.save(existingTeam);
                } else {
                    // Save new team
                    crbtTeamRepository.save(team);
                }
                savedCount++;
            } catch (Exception e) {
                log.error("❌ Error saving team {}: {}", team.getCrbtId(), e.getMessage());
            }
        }

        log.info("✅ Processed {} teams from CRBT API, {} saved/updated", crbtTeams.size(), savedCount);
    }

    /**
     * Load team memberships from CRBT API using the client interface
     */
    private void loadTeamMembershipsFromCrbt() {
        log.info("🔗 Loading team memberships from CRBT API...");

        List<CrbtApiClient.CrbtApiTeamResponse> teamResponses = crbtApiClient.fetchAllTeamsWithMembers();
        log.info("📥 Retrieved {} teams with member details", teamResponses.size());

        int membershipCount = 0;
        for (CrbtApiClient.CrbtApiTeamResponse teamResponse : teamResponses) {
            try {
                // Find the team in our database
                Optional<CrbtTeam> teamOpt = crbtTeamRepository.findById(teamResponse.crbtID);
                if (teamOpt.isEmpty()) {
                    log.warn("⚠️ Team {} not found in database, skipping memberships", teamResponse.crbtID);
                    continue;
                }

                CrbtTeam team = teamOpt.get();

                // Process each member
                if (teamResponse.memberList != null) {
                    for (CrbtApiClient.CrbtApiTeamResponse.CrbtApiMember member : teamResponse.memberList) {
                        try {
                            // Find the user by employee ID
                            Optional<AppUser> userOpt = appUserRepository.findByEmployeeId(member.mbrEmplId);
                            if (userOpt.isEmpty()) {
                                log.debug("⚠️ User with employee ID {} not found, skipping membership", member.mbrEmplId);
                                continue;
                            }

                            AppUser user = userOpt.get();

                            // Create or update membership
                            UserTeamMembershipId membershipId = new UserTeamMembershipId(user.getUsername(), team.getCrbtId());
                            Optional<UserTeamMembership> existingMembership = userTeamMembershipRepository.findById(membershipId);

                            if (existingMembership.isPresent()) {
                                // Update existing membership
                                UserTeamMembership membership = existingMembership.get();
                                membership.setMemberRole(member.mbrRoleCd);
                                userTeamMembershipRepository.save(membership);
                            } else {
                                // Create new membership
                                UserTeamMembership newMembership = new UserTeamMembership();
                                newMembership.setId(membershipId);
                                newMembership.setUser(user);
                                newMembership.setTeam(team);
                                newMembership.setMemberRole(member.mbrRoleCd);
                                userTeamMembershipRepository.save(newMembership);
                            }

                            membershipCount++;
                        } catch (Exception e) {
                            log.error("❌ Error processing member {}: {}", member.mbrEmplId, e.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                log.error("❌ Error processing team {}: {}", teamResponse.crbtID, e.getMessage());
            }
        }

        log.info("✅ Processed team memberships, {} memberships saved/updated", membershipCount);
    }

    /**
     * Update existing user with latest AD data
     */
    private void updateUserFromAdData(AppUser existing, AppUser adData) {
        existing.setFirstName(adData.getFirstName());
        existing.setLastName(adData.getLastName());
        existing.setTitle(adData.getTitle());
        existing.setDistinguishedName(adData.getDistinguishedName());
        existing.setCountry(adData.getCountry());
        existing.setActive(adData.isActive());
        existing.setManagerUsername(adData.getManagerUsername());
    }

    /**
     * Log summary of loaded data
     */
    private void logDataLoadingSummary() {
        long userCount = appUserRepository.count();
        long teamCount = crbtTeamRepository.count();
        long membershipCount = userTeamMembershipRepository.count();

        log.info("📊 Data Loading Summary:");
        log.info("   👥 Users: {}", userCount);
        log.info("   🏢 Teams: {}", teamCount);
        log.info("   🔗 Memberships: {}", membershipCount);
        log.info("   🕐 Completed at: {}", LocalDateTime.now());
    }
}
