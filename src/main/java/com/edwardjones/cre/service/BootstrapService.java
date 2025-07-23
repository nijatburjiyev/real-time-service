package com.edwardjones.cre.service;

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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class BootstrapService implements ApplicationRunner {

    private final AdLdapClient adLdapClient;
    private final CrbtApiClient crbtApiClient;
    private final AppUserRepository appUserRepository;
    private final CrbtTeamRepository crbtTeamRepository;
    private final UserTeamMembershipRepository userTeamMembershipRepository;

    @Override
    public void run(ApplicationArguments args) {
        if (appUserRepository.count() > 0) {
            log.info("State database already populated. Skipping bootstrap.");
            return;
        }
        log.info("Starting bootstrap process...");

        List<AppUser> usersFromAd = adLdapClient.fetchAllUsers();

        Set<String> leaderUsernames = usersFromAd.stream()
            .map(AppUser::getManagerUsername)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());

        usersFromAd.forEach(user -> {
            boolean isLeader = leaderUsernames.contains(user.getUsername());
            boolean isBranch = user.getDistinguishedName() != null && user.getDistinguishedName().contains("OU=Branch");
            if (isLeader && isBranch) {
                user.setFinancialAdvisor(true);
            }
        });

        List<CrbtApiClient.CrbtApiTeamResponse> allTeamResponses = usersFromAd.stream()
            .filter(AppUser::isFinancialAdvisor)
            .flatMap(fa -> crbtApiClient.fetchTeamsForLeader(fa.getUsername()).stream())
            .distinct()
            .toList();

        persistData(usersFromAd, allTeamResponses);
        log.info("Bootstrap process completed successfully.");
    }

    @Transactional
    public void persistData(List<AppUser> users, List<CrbtApiClient.CrbtApiTeamResponse> teamResponses) {
        log.info("Persisting all data in a single transaction...");

        // 1. Clear existing data
        userTeamMembershipRepository.deleteAllInBatch();
        crbtTeamRepository.deleteAllInBatch();
        appUserRepository.deleteAllInBatch();
        log.info("Phase 1: Old data cleared.");

        if (users.isEmpty()) {
            log.warn("No users fetched from AD. Bootstrap will result in an empty state.");
            return;
        }

        // --- START OF THE DEFINITIVE FIX: TWO-PASS USER PERSISTENCE ---

        // 2. First Pass: Save all users with manager links COMPLETELY removed.
        log.info("Phase 2 (Pass 1): Saving {} user records without manager links...", users.size());

        // Temporarily store the manager links in memory
        Map<String, String> managerLinks = new HashMap<>();
        users.forEach(user -> {
            if (user.getManagerUsername() != null) {
                managerLinks.put(user.getUsername(), user.getManagerUsername());
                user.setManagerUsername(null); // <-- CRITICAL: Explicitly nullify the FK field
            }
        });

        appUserRepository.saveAllAndFlush(users);
        log.info("Phase 2 (Pass 1): All users saved without manager links.");

        // 3. Second Pass: Now that all users exist, re-apply the manager links.
        log.info("Phase 2 (Pass 2): Establishing manager relationships...");
        List<AppUser> usersToUpdate = new ArrayList<>();
        managerLinks.forEach((username, managerUsername) -> {
            // Find the user we just saved
            appUserRepository.findById(username).ifPresent(user -> {
                // Set the manager username back to its original value
                user.setManagerUsername(managerUsername);
                usersToUpdate.add(user);
            });
        });

        appUserRepository.saveAll(usersToUpdate);
        log.info("Phase 2 (Pass 2): Manager relationships established for {} users.", usersToUpdate.size());

        // --- END OF THE DEFINITIVE FIX ---

        // 4. Save teams (this logic is correct and unchanged)
        Map<Integer, CrbtTeam> teamMap = new HashMap<>();
        teamResponses.forEach(dto -> {
            if (dto.crbtID == null) return;
            CrbtTeam team = new CrbtTeam();
            team.setCrbtId(dto.crbtID);
            team.setTeamName(dto.teamName);
            team.setTeamType(dto.teamTyCd);
            team.setActive(dto.tmEndDa == null);
            teamMap.put(team.getCrbtId(), team);
        });
        crbtTeamRepository.saveAll(teamMap.values());
        log.info("Phase 3: Saved {} unique teams.", teamMap.size());

        // 5. Save memberships (this logic is correct and unchanged)
        List<UserTeamMembership> memberships = new ArrayList<>();
        teamResponses.forEach(dto -> {
            if (dto.memberList == null || dto.crbtID == null) return;
            dto.memberList.forEach(memberDto -> {
                if (memberDto.mbrJorP == null) return;
                appUserRepository.findById(memberDto.mbrJorP).ifPresent(user -> {
                    CrbtTeam team = teamMap.get(dto.crbtID);
                    if (team != null) {
                        UserTeamMembership membership = new UserTeamMembership(user, team);
                        membership.setMemberRole(memberDto.mbrRoleCd);
                        memberships.add(membership);
                    }
                });
            });
        });
        userTeamMembershipRepository.saveAll(memberships);
        log.info("Phase 4: Saved {} team memberships.", memberships.size());
        log.info("Successfully persisted all data.");
    }
}
