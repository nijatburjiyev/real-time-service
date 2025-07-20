package com.edwardjones.cre.service.bootstrap;

import com.edwardjones.cre.client.AdLdapClient;
import com.edwardjones.cre.client.CrbtApiClient;
import com.edwardjones.cre.repository.AppUserRepository;
import com.edwardjones.cre.repository.CrbtTeamRepository;
import com.edwardjones.cre.repository.UserTeamMembershipRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class BootstrapService {
    private final AdLdapClient adLdapClient;
    private final CrbtApiClient crbtApiClient;
    private final AppUserRepository appUserRepository;
    private final CrbtTeamRepository crbtTeamRepository;
    private final UserTeamMembershipRepository userTeamMembershipRepository;

    @PostConstruct
    @Transactional
    public void initializeState() {
        System.out.println("--- STARTING BOOTSTRAP PROCESS ---");

        // Clear old state
        appUserRepository.deleteAll();
        crbtTeamRepository.deleteAll();
        userTeamMembershipRepository.deleteAll();

        // Fetch from sources
        var users = adLdapClient.fetchAllUsers();
        var teams = crbtApiClient.fetchAllTeams();

        // Populate H2
        appUserRepository.saveAll(users);
        crbtTeamRepository.saveAll(teams);
        // TODO: Fetch and save team memberships

        System.out.println("--- BOOTSTRAP PROCESS COMPLETE ---");
    }
}
