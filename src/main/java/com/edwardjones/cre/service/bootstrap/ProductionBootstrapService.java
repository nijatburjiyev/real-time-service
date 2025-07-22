package com.edwardjones.cre.service.bootstrap;


import com.edwardjones.cre.client.AdLdapClient;
import com.edwardjones.cre.client.CrbtApiClient;
import com.edwardjones.cre.model.domain.AppUser;
import com.edwardjones.cre.model.domain.CrbtTeam;
import com.edwardjones.cre.model.domain.UserTeamMembership;
import com.edwardjones.cre.repository.AppUserRepository;
import com.edwardjones.cre.repository.CrbtTeamRepository;
import com.edwardjones.cre.repository.UserTeamMembershipRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The single, authoritative service for bootstrapping the application's state.
 * This service is responsible for creating a clean, consistent snapshot of the
 * world from source systems (AD, CRBT) on application startup.
 *
 * It follows a robust "Fetch, then Process & Persist" pattern to ensure
 * transactional integrity and resilience.
 */
@Slf4j
@Service
@Profile("!test") // This service will not run during unit/integration tests
@RequiredArgsConstructor
public class ProductionBootstrapService implements ApplicationRunner {

    private final AdLdapClient adLdapClient;
    private final CrbtApiClient crbtApiClient;
    private final AppUserRepository appUserRepository;
    private final CrbtTeamRepository crbtTeamRepository;
    private final UserTeamMembershipRepository userTeamMembershipRepository;

    /**
     * The main entry point for the bootstrap process, triggered after the
     * application context is fully loaded.
     */
    @Override
    public void run(ApplicationArguments args) {
        log.info("🚀 Starting production bootstrap process...");

        try {
            // --- PHASE 1: FETCH (No Database Transaction) ---
            // Fetch all data from external network sources first. This keeps the
            // database transaction short and avoids holding locks during network I/O.
            log.info("FETCH PHASE: Retrieving data from AD and CRBT...");
            List<AppUser> usersFromAd = adLdapClient.fetchAllUsers();
            List<CrbtApiClient.CrbtApiTeamResponse> teamsWithMembers = crbtApiClient.fetchAllTeamsWithMembers();
            log.info("FETCH PHASE: Retrieved {} users from AD and {} teams from CRBT.", usersFromAd.size(), teamsWithMembers.size());

            // --- PHASE 2: PROCESS & PERSIST (Single Database Transaction) ---
            // Now, perform all database operations within a single, atomic transaction.
            persistStateToDatabase(usersFromAd, teamsWithMembers);

            log.info("✅ Bootstrap process completed successfully.");

        } catch (Exception e) {
            log.error("💥 CRITICAL: Bootstrap process failed. The application state may be inconsistent. Please investigate.", e);
            // In a real production scenario, you might want to shut down the application
            // or prevent it from connecting to Kafka if the bootstrap fails.
            throw new RuntimeException("Bootstrap failed", e);
        }
    }

    /**
     * This method is annotated as @Transactional. All operations within it will
     * either succeed together or fail together, ensuring data consistency.
     */
    @Transactional
    public void persistStateToDatabase(List<AppUser> usersFromAd, List<CrbtApiClient.CrbtApiTeamResponse> teamsWithMembers) {
        log.info("PERSIST PHASE: Starting database transaction...");

        // 1. Wipe all existing state for a clean slate
        log.info("PERSIST PHASE: Clearing old data...");
        userTeamMembershipRepository.deleteAllInBatch();
        appUserRepository.deleteAllInBatch();
        crbtTeamRepository.deleteAllInBatch();
        log.info("PERSIST PHASE: Old data cleared.");

        // 2. Process and Save Users, handling manager relationships correctly
        if (usersFromAd.isEmpty()) {
            log.warn("No users fetched from AD. The database will be empty.");
            return;
        }

        // Create a map for efficient lookup (Username -> AppUser object)
        Map<String, AppUser> userMap = usersFromAd.stream()
                .collect(Collectors.toMap(AppUser::getUsername, Function.identity()));

        // Set the manager object references in memory before saving.
        // This allows JPA to correctly handle the insert order.
        userMap.values().forEach(user -> {
            if (user.getManagerUsername() != null) {
                AppUser manager = userMap.get(user.getManagerUsername());
                if (manager != null) {
                    user.setManager(manager);
                } else {
                    log.warn("Manager '{}' for user '{}' not found in the dataset. Setting manager to null.",
                            user.getManagerUsername(), user.getUsername());
                    user.setManagerUsername(null); // Clean up invalid reference
                }
            }
        });

        appUserRepository.saveAll(userMap.values());
        log.info("PERSIST PHASE: Saved {} users to the database.", userMap.size());

        // 3. Process and Save Teams
        Set<CrbtTeam> teamsToSave = teamsWithMembers.stream()
                .map(t -> {
                    CrbtTeam team = new CrbtTeam();
                    team.setCrbtId(t.crbtID);
                    team.setTeamName(t.teamName);
                    team.setTeamType(t.teamTyCd);
                    team.setActive(t.tmEndDa == null);
                    return team;
                })
                .collect(Collectors.toSet());

        crbtTeamRepository.saveAll(teamsToSave);
        log.info("PERSIST PHASE: Saved {} teams to the database.", teamsToSave.size());

        // 4. Process and Save Team Memberships
        Map<Integer, CrbtTeam> teamMap = teamsToSave.stream()
                .collect(Collectors.toMap(CrbtTeam::getCrbtId, Function.identity()));

        List<UserTeamMembership> memberships = teamsWithMembers.stream()
                .flatMap(teamResponse -> {
                    CrbtTeam team = teamMap.get(teamResponse.crbtID);
                    if (team == null || teamResponse.memberList == null) {
                        return null;
                    }
                    return teamResponse.memberList.stream()
                            .map(member -> {
                                AppUser user = userMap.get(member.mbrJorP);
                                if (user == null) {
                                    log.warn("User '{}' from CRBT team {} not found in AD data. Skipping membership.", member.mbrJorP, team.getCrbtId());
                                    return null;
                                }
                                UserTeamMembership membership = new UserTeamMembership(user, team);
                                membership.setMemberRole(member.mbrRoleCd);
                                // You can parse real dates here if available
                                membership.setEffectiveStartDate(LocalDate.now().minusDays(30));
                                return membership;
                            });
                })
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());

        userTeamMembershipRepository.saveAll(memberships);
        log.info("PERSIST PHASE: Saved {} team memberships to the database.", memberships.size());
        log.info("PERSIST PHASE: Committing transaction...");
    }
}