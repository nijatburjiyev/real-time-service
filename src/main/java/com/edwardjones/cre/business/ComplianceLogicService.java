package com.edwardjones.cre.business;

import com.edwardjones.cre.model.domain.AppUser;
import com.edwardjones.cre.model.domain.UserTeamMembership;
import com.edwardjones.cre.repository.AppUserRepository;
import com.edwardjones.cre.repository.UserTeamMembershipRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class ComplianceLogicService {

    private final AppUserRepository appUserRepository;
    private final UserTeamMembershipRepository userTeamMembershipRepository;

    /**
     * Calculates the complete compliance configuration for a user based on their
     * organizational hierarchy, team memberships, and business rules.
     */
    public AppUser calculateConfigurationForUser(String username) {
        log.debug("Calculating configuration for user: {}", username);

        Optional<AppUser> userOpt = appUserRepository.findById(username);
        if (userOpt.isEmpty()) {
            log.warn("User not found: {}", username);
            return null;
        }

        AppUser user = userOpt.get();

        // Create a copy to avoid modifying the original entity
        AppUser configuredUser = createUserCopy(user);

        // Calculate visibility profile based on user attributes
        String visibilityProfile = calculateVisibilityProfile(configuredUser);
        configuredUser.setCalculatedVisibilityProfile(visibilityProfile);

        // Calculate group memberships based on team roles and organizational hierarchy
        Set<String> groups = calculateGroups(configuredUser);
        configuredUser.setCalculatedGroups(groups);

        log.debug("Calculated configuration for {}: profile={}, groups={}",
                 username, visibilityProfile, groups);

        return configuredUser;
    }

    /**
     * Calculates the visibility profile based on user's organizational position and team roles
     */
    private String calculateVisibilityProfile(AppUser user) {
        if (!user.isActive()) {
            return "INACTIVE";
        }

        // Get user's team memberships
        List<UserTeamMembership> memberships = userTeamMembershipRepository.findByUserUsername(user.getUsername());

        // Check if user has leadership roles
        boolean isTeamLead = memberships.stream()
                .anyMatch(membership -> "LEAD".equalsIgnoreCase(membership.getMemberRole()));

        // Check if user is a manager (has direct reports)
        List<AppUser> directReports = appUserRepository.findByManagerUsername(user.getUsername());
        boolean isManager = !directReports.isEmpty();

        if (isTeamLead || isManager) {
            return "MANAGER_PROFILE";
        }

        // Check for senior roles based on title
        if (user.getTitle() != null && (
                user.getTitle().toLowerCase().contains("senior") ||
                user.getTitle().toLowerCase().contains("lead") ||
                user.getTitle().toLowerCase().contains("principal"))) {
            return "SENIOR_PROFILE";
        }

        return "STANDARD_PROFILE";
    }

    /**
     * Calculates group memberships based on team roles, geography, and organizational hierarchy
     */
    private Set<String> calculateGroups(AppUser user) {
        Set<String> groups = new HashSet<>();

        if (!user.isActive()) {
            groups.add("INACTIVE_USERS");
            return groups;
        }

        // Base group for all active users
        groups.add("ALL_USERS");

        // Geography-based groups
        if (user.getCountry() != null) {
            groups.add("COUNTRY_" + user.getCountry().toUpperCase());
        }

        // Team-based groups
        List<UserTeamMembership> memberships = userTeamMembershipRepository.findByUserUsername(user.getUsername());
        for (UserTeamMembership membership : memberships) {
            if (membership.getTeam() != null && membership.getTeam().isActive()) {
                String teamType = membership.getTeam().getTeamType();
                if (teamType != null) {
                    groups.add("TEAM_" + teamType.toUpperCase());
                }

                // Role-specific groups within teams
                String role = membership.getMemberRole();
                if (role != null) {
                    groups.add("ROLE_" + role.toUpperCase());
                    groups.add("TEAM_" + teamType.toUpperCase() + "_" + role.toUpperCase());
                }
            }
        }

        // Organizational hierarchy groups
        if (user.getDistinguishedName() != null) {
            // Extract OU information from DN
            if (user.getDistinguishedName().contains("OU=STL")) {
                groups.add("OU_STL");
            }
            if (user.getDistinguishedName().contains("OU=Branch")) {
                groups.add("OU_BRANCH");
            }
        }

        // Manager groups
        List<AppUser> directReports = appUserRepository.findByManagerUsername(user.getUsername());
        if (!directReports.isEmpty()) {
            groups.add("MANAGERS");
            groups.add("TEAM_SIZE_" + categorizeTeamSize(directReports.size()));
        }

        return groups;
    }

    /**
     * Creates a copy of the user for configuration calculations
     */
    private AppUser createUserCopy(AppUser original) {
        AppUser copy = new AppUser();
        copy.setUsername(original.getUsername());
        copy.setEmployeeId(original.getEmployeeId());
        copy.setFirstName(original.getFirstName());
        copy.setLastName(original.getLastName());
        copy.setTitle(original.getTitle());
        copy.setManagerUsername(original.getManagerUsername());
        copy.setDistinguishedName(original.getDistinguishedName());
        copy.setCountry(original.getCountry());
        copy.setActive(original.isActive());
        copy.setHireDate(original.getHireDate());
        copy.setLastUpdated(original.getLastUpdated());
        return copy;
    }

    /**
     * Categorizes team size for group assignment
     */
    private String categorizeTeamSize(int teamSize) {
        if (teamSize <= 5) return "SMALL";
        if (teamSize <= 15) return "MEDIUM";
        return "LARGE";
    }
}
