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
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

@Slf4j
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
        log.info("--- STARTING BOOTSTRAP PROCESS ---");

        // Clear old state
        userTeamMembershipRepository.deleteAll();
        appUserRepository.deleteAll();
        crbtTeamRepository.deleteAll();

        // Fetch from sources
        var users = adLdapClient.fetchAllUsers();
        var teams = crbtApiClient.fetchAllTeams();

        // Populate H2 with basic entities first
        appUserRepository.saveAll(users);
        crbtTeamRepository.saveAll(teams);

        log.info("Bootstrap: Saved {} users and {} teams", users.size(), teams.size());

        // Now fetch teams with member details and create memberships
        createTeamMembershipsFromCrbtApi();

        log.info("--- BOOTSTRAP PROCESS COMPLETE ---");
    }

    /**
     * Create team memberships using the new CRBT API format with memberList
     */
    private void createTeamMembershipsFromCrbtApi() {
        log.info("Creating team memberships from CRBT API data...");

        var teamsWithMembers = crbtApiClient.fetchAllTeamsWithMembers();
        int membershipCount = 0;

        for (CrbtApiClient.CrbtApiTeamResponse teamResponse : teamsWithMembers) {
            // Ensure the team exists in our database
            Optional<CrbtTeam> teamOpt = crbtTeamRepository.findById(teamResponse.crbtID);
            if (teamOpt.isEmpty()) {
                log.warn("Team {} not found in database during membership creation", teamResponse.crbtID);
                continue;
            }

            CrbtTeam team = teamOpt.get();

            // Create memberships for each member in the team
            for (CrbtApiClient.CrbtApiTeamResponse.CrbtApiMember member : teamResponse.memberList) {
                // FIXED: Find the user by their employee ID (mbrEmplId) instead of mbrJorP
                Optional<AppUser> userOpt = appUserRepository.findByEmployeeId(member.mbrEmplId);
                if (userOpt.isEmpty()) {
                    log.warn("User with employeeId {} (P/J: {}) not found in database for team membership",
                            member.mbrEmplId, member.mbrJorP);
                    continue;
                }

                AppUser user = userOpt.get();

                // Create the membership
                UserTeamMembershipId membershipId = new UserTeamMembershipId(user.getUsername(), team.getCrbtId());
                UserTeamMembership membership = new UserTeamMembership();
                membership.setId(membershipId);
                membership.setUser(user);
                membership.setTeam(team);
                membership.setMemberRole(member.mbrRoleCd);

                // FIXED: Parse actual dates from CRT data instead of hardcoded values
                membership.setEffectiveStartDate(parseCrtDate(member.mbrBegDa));
                membership.setEffectiveEndDate(parseCrtDate(member.mbrEndDa));

                userTeamMembershipRepository.save(membership);
                membershipCount++;

                log.debug("Created membership: {} (empId: {}) in team {} as {}",
                    user.getUsername(), user.getEmployeeId(), team.getCrbtId(), member.mbrRoleCd);
            }
        }

        log.info("Bootstrap: Created {} team memberships from CRBT API data", membershipCount);

        // Log some interesting findings for testing
        logBootstrapSummary(teamsWithMembers);
    }

    /**
     * Parse date strings from different sources (CRT Kafka CDC, CRT REST API, and AD)
     * CRT Kafka CDC format: "2025-07-19" (yyyy-MM-dd)
     * CRT REST API format: "2025-05-12T00:00:00.000+00:00" (ISO)
     * AD format: "07-01-2025" (MM-dd-yyyy)
     */
    private LocalDate parseDateFromAnySource(String dateString) {
        if (dateString == null || dateString.trim().isEmpty()) {
            return null;
        }

        try {
            // Try CRT REST API ISO format first
            if (dateString.contains("T")) {
                return LocalDate.parse(dateString.substring(0, 10));
            }

            // Try CRT Kafka CDC format (yyyy-MM-dd)
            if (dateString.matches("\\d{4}-\\d{2}-\\d{2}")) {
                return LocalDate.parse(dateString);
            }

            // Try AD format (MM-dd-yyyy)
            if (dateString.matches("\\d{2}-\\d{2}-\\d{4}")) {
                DateTimeFormatter adFormatter = DateTimeFormatter.ofPattern("MM-dd-yyyy");
                return LocalDate.parse(dateString, adFormatter);
            }

            // Fallback - try standard ISO date format
            return LocalDate.parse(dateString);

        } catch (Exception e) {
            log.warn("Could not parse date '{}' from any known format, using current date", dateString);
            return LocalDate.now();
        }
    }

    /**
     * Parse CRT date format to LocalDate (keeping for backward compatibility)
     * CRT format: "2025-05-12T00:00:00.000+00:00"
     */
    private LocalDate parseCrtDate(String crtDateString) {
        return parseDateFromAnySource(crtDateString);
    }

    /**
     * Log interesting findings from the bootstrap process for testing validation
     */
    private void logBootstrapSummary(java.util.List<CrbtApiClient.CrbtApiTeamResponse> teamsWithMembers) {
        log.info("=== BOOTSTRAP SUMMARY ===");

        // Find users with multiple team memberships
        for (CrbtApiClient.CrbtApiTeamResponse team : teamsWithMembers) {
            for (CrbtApiClient.CrbtApiTeamResponse.CrbtApiMember member : team.memberList) {
                long membershipCount = teamsWithMembers.stream()
                    .mapToLong(t -> t.memberList.stream()
                        .filter(m -> m.mbrJorP.equals(member.mbrJorP))
                        .count())
                    .sum();

                if (membershipCount > 1) {
                    log.info("Multi-team user found: {} is in {} teams (tests precedence logic)",
                        member.mbrJorP, membershipCount);
                }
            }
        }

        // Log team type distribution
        log.info("Team types loaded: {}",
            teamsWithMembers.stream()
                .map(t -> t.teamTyCd)
                .distinct()
                .toList());

        log.info("=== END BOOTSTRAP SUMMARY ===");
    }
}
