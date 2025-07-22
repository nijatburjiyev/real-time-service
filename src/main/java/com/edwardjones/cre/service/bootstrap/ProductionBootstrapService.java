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
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The single, authoritative service for bootstrapping the application's state.
 * This service implements the corrected "discover then detail" logic that mirrors
 * the PowerShell script approach, accounting for the CRBT API's lookup-based design.
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
        if (appUserRepository.count() > 0) {
            log.info("✅ State database already contains {} users. Skipping bootstrap.", appUserRepository.count());
            return;
        }
        log.info("[BOOTSTRAP] 🚀 Starting production bootstrap process...");

        try {
            // --- PHASE 1: FETCH (No Database Transaction) ---
            log.info("[BOOTSTRAP] FETCH PHASE 1A: Retrieving all users from AD...");
            List<AppUser> usersFromAd = adLdapClient.fetchAllUsers();
            log.info("[BOOTSTRAP] FETCH PHASE 1A Complete: Retrieved {} users from AD.", usersFromAd.size());

            log.info("[BOOTSTRAP] FETCH PHASE 1B: Discovering all unique CRBT teams by querying for each Branch Leader...");
            Set<Integer> uniqueTeamIds = discoverAllTeamIds(usersFromAd);
            log.info("[BOOTSTRAP] FETCH PHASE 1B Complete: Discovered {} unique CRBT teams.", uniqueTeamIds.size());

            log.info("[BOOTSTRAP] FETCH PHASE 1C: Retrieving full details for each unique team...");
            List<CrbtApiClient.CrbtApiTeamResponse> teamsWithMembers = fetchAllTeamDetails(uniqueTeamIds);
            log.info("[BOOTSTRAP] FETCH PHASE 1C Complete: Retrieved full details for {} teams.", teamsWithMembers.size());

            // --- PHASE 2: PROCESS & PERSIST (Single Database Transaction) ---
            persistStateToDatabase(usersFromAd, teamsWithMembers);

            log.info("[BOOTSTRAP] ✅ Bootstrap process completed successfully.");

        } catch (Exception e) {
            log.error("[BOOTSTRAP] 💥 CRITICAL: Bootstrap process failed. The application state may be inconsistent.", e);
            throw new RuntimeException("Bootstrap failed, preventing application startup.", e);
        }
    }

    /**
     * Discovers all unique CRBT team IDs by identifying Branch Leaders in AD
     * and querying the CRBT API for their teams. This mirrors the PowerShell
     * script's "discover then detail" approach.
     */
    private Set<Integer> discoverAllTeamIds(List<AppUser> allUsers) {
        // Identify all users who are designated as managers in the AD data
        Set<String> managerUsernames = allUsers.stream()
                .map(AppUser::getManagerUsername)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        // Filter to find users who are both managers AND in a Branch OU
        List<AppUser> branchLeaders = allUsers.stream()
                .filter(user -> managerUsernames.contains(user.getUsername()))
                .filter(user -> user.getDistinguishedName() != null &&
                               user.getDistinguishedName().contains("OU=Branch"))
                .toList();

        log.info("Identified {} potential Branch Leaders to query for teams.", branchLeaders.size());

        // For each leader, call the API to find their teams and collect unique IDs
        // Using parallel stream for performance on this network-intensive operation
        return branchLeaders.parallelStream()
                .flatMap(leader -> {
                    try {
                        return crbtApiClient.fetchTeamsForLeader(leader.getUsername()).stream();
                    } catch (Exception e) {
                        log.warn("Failed to fetch teams for leader '{}': {}", leader.getUsername(), e.getMessage());
                        return Stream.empty();
                    }
                })
                .map(teamResponse -> teamResponse.crbtID)
                .collect(Collectors.toSet());
    }

    /**
     * Fetches full team details (including member lists) for all discovered team IDs.
     */
    private List<CrbtApiClient.CrbtApiTeamResponse> fetchAllTeamDetails(Set<Integer> teamIds) {
        if (teamIds.isEmpty()) {
            log.warn("No team IDs discovered. No team details will be fetched.");
            return Collections.emptyList();
        }

        // For each unique team ID, get its full member list
        return teamIds.parallelStream()
                .flatMap(id -> {
                    try {
                        return crbtApiClient.fetchTeamDetails(id).stream();
                    } catch (Exception e) {
                        log.warn("Failed to fetch details for team ID {}: {}", id, e.getMessage());
                        return Stream.empty();
                    }
                })
                .toList();
    }

    /**
     * This method is annotated as @Transactional. All operations within it will
     * either succeed together or fail together, ensuring data consistency.
     *
     * CRITICAL FIX: Two-pass approach - save users first, then establish manager
     * relationships to prevent EntityNotFoundException crashes.
     */
    @Transactional
    public void persistStateToDatabase(List<AppUser> usersFromAd,
                                     List<CrbtApiClient.CrbtApiTeamResponse> teamsWithMembers) {
        log.info("[BOOTSTRAP] PERSIST PHASE: Starting database transaction...");

        // 1. Wipe all existing state for a clean slate
        userTeamMembershipRepository.deleteAllInBatch();
        appUserRepository.deleteAllInBatch();
        crbtTeamRepository.deleteAllInBatch();
        log.info("[BOOTSTRAP] PERSIST PHASE: Old data cleared.");

        if (usersFromAd.isEmpty()) {
            log.warn("[BOOTSTRAP] No users fetched from AD. The state database will be empty.");
            return;
        }

        // 2. Save all users first, ignoring manager links for now.
        // This ensures all potential managers exist in the DB before we try to link to them.
        usersFromAd.forEach(user -> {
            user.setManager(null); // Clear any in-memory manager references
        });
        appUserRepository.saveAll(usersFromAd);
        appUserRepository.flush(); // Force the insert statements to execute
        log.info("[BOOTSTRAP] PERSIST PHASE (PASS 1): Saved {} raw user records to the database.", usersFromAd.size());

        // 3. Now, link managers in a second pass.
        int linkedCount = 0;
        int skippedCount = 0;
        List<AppUser> usersToUpdate = new ArrayList<>();
        for (AppUser user : usersFromAd) {
            if (user.getManagerUsername() != null) {
                // Check if the manager actually exists in our database.
                if (appUserRepository.existsById(user.getManagerUsername())) {
                    // The manager exists, so this is a valid link.
                    // We don't need to set the object; the FK string is enough.
                    usersToUpdate.add(user);
                    linkedCount++;
                } else {
                    // This is the critical fix: the manager doesn't exist in our dataset.
                    // Log it and nullify the invalid reference before saving.
                    log.warn("[BOOTSTRAP][DATA_INTEGRITY] Manager '{}' for user '{}' not found in the dataset. This link will be permanently ignored.",
                            user.getManagerUsername(), user.getUsername());
                    user.setManagerUsername(null);
                    usersToUpdate.add(user); // Still need to save the nulled-out manager username
                    skippedCount++;
                }
            }
        }
        appUserRepository.saveAll(usersToUpdate);
        log.info("[BOOTSTRAP] PERSIST PHASE (PASS 2): Processed manager links for {} users. {} links were invalid and skipped.", linkedCount, skippedCount);

        // 4. Process and Save Teams (unchanged)
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
        log.info("[BOOTSTRAP] PERSIST PHASE: Saved {} teams to the database.", teamsToSave.size());

        // 5. Process and Save Team Memberships (with added resilience)
        Map<Integer, CrbtTeam> teamMap = teamsToSave.stream()
                .collect(Collectors.toMap(CrbtTeam::getCrbtId, Function.identity()));

        List<UserTeamMembership> memberships = new ArrayList<>();
        for (CrbtApiClient.CrbtApiTeamResponse teamResponse : teamsWithMembers) {
            if (teamResponse.memberList == null) continue;

            CrbtTeam team = teamMap.get(teamResponse.crbtID);
            for (CrbtApiClient.CrbtApiTeamResponse.CrbtApiMember member : teamResponse.memberList) {
                // Use existsById for a fast check before a full fetch
                if (appUserRepository.existsById(member.mbrJorP)) {
                    AppUser user = appUserRepository.getReferenceById(member.mbrJorP); // Use getReference for performance
                    UserTeamMembership membership = new UserTeamMembership(user, team);
                    membership.setMemberRole(member.mbrRoleCd);
                    membership.setEffectiveStartDate(LocalDate.now()); // Placeholder
                    memberships.add(membership);
                } else {
                    log.warn("[BOOTSTRAP][DATA_INTEGRITY] User '{}' from CRBT team {} not found in AD data. Skipping membership.",
                            member.mbrJorP, team.getCrbtId());
                }
            }
        }

        userTeamMembershipRepository.saveAll(memberships);
        log.info("[BOOTSTRAP] PERSIST PHASE: Saved {} team memberships to the database.", memberships.size());
        log.info("[BOOTSTRAP] PERSIST PHASE: Committing transaction...");
    }
}
