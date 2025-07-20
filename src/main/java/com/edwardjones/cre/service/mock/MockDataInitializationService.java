package com.edwardjones.cre.service.mock;

import com.edwardjones.cre.model.domain.AppUser;
import com.edwardjones.cre.model.domain.CrbtTeam;
import com.edwardjones.cre.model.domain.UserTeamMembership;
import com.edwardjones.cre.model.domain.UserTeamMembershipId;
import com.edwardjones.cre.repository.AppUserRepository;
import com.edwardjones.cre.repository.CrbtTeamRepository;
import com.edwardjones.cre.repository.UserTeamMembershipRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * Service to create realistic team memberships after bootstrap completes.
 * This simulates the complex relationships that would exist in a real environment.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MockDataInitializationService {

    private final AppUserRepository appUserRepository;
    private final CrbtTeamRepository crbtTeamRepository;
    private final UserTeamMembershipRepository userTeamMembershipRepository;

    @EventListener(ApplicationReadyEvent.class)
    @Order(1) // Run after bootstrap but before event simulation
    public void initializeMockTeamMemberships() {
        log.info("=== Initializing Mock Team Memberships ===");

        // Wait for bootstrap to complete
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        createTeamMemberships();

        log.info("=== Mock Team Memberships Initialized ===");
    }

    private void createTeamMemberships() {
        // Create memberships that will test different business logic scenarios

        // Scenario 1: User in multiple teams (tests precedence logic)
        // Lisa Brown (p45678) in both VTM and HTM teams (VTM should take precedence)
        createMembership("p45678", 312595, "BOA", LocalDate.now().minusDays(30)); // VTM team
        createMembership("p45678", 312596, "BOA", LocalDate.now().minusDays(20)); // HTM team

        // Scenario 2: Standard single team memberships
        createMembership("p23456", 312596, "FA", LocalDate.now().minusDays(60)); // Sarah in HTM
        createMembership("p34567", 312597, "BOA", LocalDate.now().minusDays(45)); // Mike in SFA
        createMembership("p56789", 312598, "LEAD", LocalDate.now().minusDays(90)); // Robert in ACM

        // Scenario 3: Branch leader in multiple teams (tests BR_TEAM logic)
        createMembership("p67890", 312595, "LEAD", LocalDate.now().minusDays(100)); // Jennifer in VTM
        createMembership("p67890", 312598, "BOA", LocalDate.now().minusDays(50)); // Jennifer in ACM

        // Scenario 4: User with ended membership (tests effective date logic)
        UserTeamMembership endedMembership = createMembership("p78901", 312599, "BOA", LocalDate.now().minusDays(120));
        if (endedMembership != null) {
            endedMembership.setEffectiveEndDate(LocalDate.now().minusDays(10)); // Ended recently
            userTeamMembershipRepository.save(endedMembership);
        }

        log.info("Created team memberships for testing various scenarios");
    }

    private UserTeamMembership createMembership(String username, Integer teamId, String role, LocalDate startDate) {
        AppUser user = appUserRepository.findById(username).orElse(null);
        CrbtTeam team = crbtTeamRepository.findById(teamId).orElse(null);

        if (user == null || team == null) {
            log.warn("Cannot create membership - user {} or team {} not found", username, teamId);
            return null;
        }

        UserTeamMembershipId id = new UserTeamMembershipId(username, teamId);
        UserTeamMembership membership = new UserTeamMembership();
        membership.setId(id);
        membership.setUser(user);
        membership.setTeam(team);
        membership.setMemberRole(role);
        membership.setEffectiveStartDate(startDate);
        membership.setEffectiveEndDate(null); // Active by default

        userTeamMembershipRepository.save(membership);
        log.debug("Created membership: {} in team {} as {}", username, teamId, role);

        return membership;
    }
}
