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
        userTeamMembershipRepository.deleteAllInBatch();
        crbtTeamRepository.deleteAllInBatch();
        appUserRepository.deleteAllInBatch();

        appUserRepository.saveAll(users);

        Map<Integer, CrbtTeam> teamMap = new HashMap<>();
        teamResponses.forEach(dto -> {
            CrbtTeam team = new CrbtTeam();
            team.setCrbtId(dto.crbtID);
            team.setTeamName(dto.teamName);
            team.setTeamType(dto.teamTyCd);
            team.setActive(dto.tmEndDa == null);
            teamMap.put(team.getCrbtId(), team);
        });
        crbtTeamRepository.saveAll(teamMap.values());

        List<UserTeamMembership> memberships = new ArrayList<>();
        teamResponses.forEach(dto -> {
            if (dto.memberList == null) return;
            dto.memberList.forEach(memberDto ->
                appUserRepository.findById(memberDto.mbrJorP).ifPresent(user -> {
                    CrbtTeam team = teamMap.get(dto.crbtID);
                    if (team != null) {
                        UserTeamMembership membership = new UserTeamMembership(user, team);
                        membership.setMemberRole(memberDto.mbrRoleCd);
                        memberships.add(membership);
                    }
                })
            );
        });
        userTeamMembershipRepository.saveAll(memberships);
        log.info("Successfully persisted {} users, {} teams, and {} memberships.", users.size(), teamMap.size(), memberships.size());
    }
}
