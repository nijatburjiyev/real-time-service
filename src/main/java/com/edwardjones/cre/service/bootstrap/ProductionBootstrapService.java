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
        log.info("🚀 Starting production bootstrap process...");

        try {
            // --- PHASE 1: FETCH (No Database Transaction) ---
            log.info("FETCH PHASE 1A: Retrieving all users from AD...");
            List<AppUser> usersFromAd = adLdapClient.fetchAllUsers();
            log.info("FETCH PHASE 1A: Retrieved {} users from AD.", usersFromAd.size());

            log.info("FETCH PHASE 1B: Discovering all unique CRBT teams by querying for each Branch Leader...");
            Set<Integer> uniqueTeamIds = discoverAllTeamIds(usersFromAd);
            log.info("FETCH PHASE 1B: Discovered {} unique CRBT teams.", uniqueTeamIds.size());

            log.info("FETCH PHASE 1C: Retrieving full details for each unique team...");
            List<CrbtApiClient.CrbtApiTeamResponse> teamsWithMembers = fetchAllTeamDetails(uniqueTeamIds);
            log.info("FETCH PHASE 1C: Retrieved full details for {} teams.", teamsWithMembers.size());

            // --- PHASE 2: PROCESS & PERSIST (Single Database Transaction) ---
            persistStateToDatabase(usersFromAd, teamsWithMembers);

            log.info("✅ Bootstrap process completed successfully.");
        } catch (Exception e) {
            log.error("💥 CRITICAL: Bootstrap process failed, preventing application startup.", e);
            throw new RuntimeException("Bootstrap failed, preventing application startup.", e);
        }
    }

    private Set<Integer> discoverAllTeamIds(List<AppUser> allUsers) {
        // First, identify who the Branch Leaders are.
        Map<String, AppUser> userMap = allUsers.stream().collect(Collectors.toMap(AppUser::getUsername, Function.identity()));
        Set<String> leaderUsernames = userMap.values().stream()
                .filter(u -> u.getManagerUsername() != null)
                .map(AppUser::getManagerUsername)
                .collect(Collectors.toSet());

        List<AppUser> branchLeaders = allUsers.stream()
                .filter(user -> leaderUsernames.contains(user.getUsername()))
                .filter(user -> user.getDistinguishedName() != null && user.getDistinguishedName().contains("OU=Branch"))
                .collect(Collectors.toList());

        log.info("Identified {} potential Branch Leaders to query for teams.", branchLeaders.size());

        // For each leader, call the API to find their teams and collect the unique IDs.
        return branchLeaders.parallelStream() // Use parallel stream for performance
                .flatMap(leader -> crbtApiClient.fetchTeamsForLeader(leader.getUsername()).stream())
                .map(teamResponse -> teamResponse.crbtID)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private List<CrbtApiClient.CrbtApiTeamResponse> fetchAllTeamDetails(Set<Integer> teamIds) {
        // For each unique team ID, get its full member list.
        return teamIds.parallelStream() // Use parallel stream for performance
                .flatMap(id -> crbtApiClient.fetchTeamDetails(id).stream())
                .collect(Collectors.toList());
    }

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
                                membership.setEffectiveStartDate(LocalDate.now());
                                return membership;
                            });
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        userTeamMembershipRepository.saveAll(memberships);
        log.info("PERSIST PHASE: Saved {} team memberships to the database.", memberships.size());
        log.info("PERSIST PHASE: Committing transaction...");
    }
}