package com.edwardjones.cre.service;

import com.edwardjones.cre.model.domain.AppUser;
import com.edwardjones.cre.model.domain.UserTeamMembership;
import com.edwardjones.cre.model.dto.DesiredConfiguration;
import com.edwardjones.cre.repository.AppUserRepository;
import com.edwardjones.cre.repository.UserTeamMembershipRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ComplianceLogicService {

    private final AppUserRepository appUserRepository;
    private final UserTeamMembershipRepository userTeamMembershipRepository;

    private enum UserType { NOT_SPECIFIED, HO, HOBR, HO_LEADER, BR, BR_TEAM }

    @Transactional(readOnly = true)
    public DesiredConfiguration calculateConfigurationForUser(String username) {
        AppUser user = appUserRepository.findById(username)
            .orElseThrow(() -> new IllegalArgumentException("User not found in state database: " + username));

        UserType userType = determineUserType(user);
        List<UserTeamMembership> memberships = userTeamMembershipRepository.findByUserUsername(username);

        String visibilityProfile;
        Set<String> groups;

        CalculatedResult multiTeamResult = generateConfigurationFromMultipleGroups(user, memberships);

        if (multiTeamResult != null) {
            visibilityProfile = multiTeamResult.visibilityProfileName();
            groups = multiTeamResult.groups();
        } else {
            CalculatedResult standardResult = generateStandardConfiguration(user, userType);
            visibilityProfile = standardResult.visibilityProfileName();
            groups = standardResult.groups();
        }

        return new DesiredConfiguration(
            user.getUsername(), user.getFirstName(), user.getLastName(),
            visibilityProfile, groups, user.isActive()
        );
    }

    private UserType determineUserType(AppUser user) {
        boolean isLeader = !appUserRepository.findByManagerUsername(user.getUsername()).isEmpty();
        String dn = Optional.ofNullable(user.getDistinguishedName()).orElse("");
        String title = Optional.ofNullable(user.getTitle()).orElse("");

        boolean isHomeOffice = dn.contains("OU=Home Office");
        boolean isBranch = dn.contains("OU=Branch") || title.matches("(?i).*(Branch|Remote Support|On-Caller).*");

        if (isLeader) {
            return isHomeOffice ? UserType.HO_LEADER : UserType.BR_TEAM;
        } else {
            if (isHomeOffice && isBranch) return UserType.HOBR;
            if (isHomeOffice) return UserType.HO;
            if (isBranch) return UserType.BR;
        }
        return UserType.NOT_SPECIFIED;
    }

    private CalculatedResult generateStandardConfiguration(AppUser user, UserType userType) {
        String country = Optional.ofNullable(user.getCountry()).orElse("US");
        String vpName;
        Set<String> groups = new HashSet<>();

        switch (userType) {
            case HO -> {
                vpName = "Vis-" + country + "-HO";
                groups.add(country + " Home Office Submitters");
            }
            case BR -> {
                vpName = "Vis-" + country + "-BR";
                groups.add(country + " Field Submitters");
            }
            case HOBR -> {
                vpName = "Vis-" + country + "-HO-BR";
                groups.add(country + " Home Office Submitters");
                groups.add(country + " Field Submitters");
            }
            case HO_LEADER -> {
                vpName = String.format("Vis_HO_%s_%s_(%s)", user.getFirstName(), user.getLastName(), user.getUsername()).replaceAll("[ .]", "_");
                groups.add(country + " Home Office Submitters");
            }
            case BR_TEAM -> {
                vpName = String.format("Vis_BR_%s_%s_(%s)", user.getFirstName(), user.getLastName(), user.getUsername()).replaceAll("[ .]", "_");
                groups.add(country + " Field Submitters");
            }
            default -> {
                vpName = "Vis-Default";
                groups.add("Default Group");
            }
        }
        return new CalculatedResult(vpName, groups);
    }

    private CalculatedResult generateConfigurationFromMultipleGroups(AppUser user, List<UserTeamMembership> memberships) {
        if (memberships.size() <= 1) return null;

        Map<String, List<UserTeamMembership>> teamsByType = memberships.stream()
            .filter(m -> m.getTeam() != null && m.getTeam().getTeamType() != null)
            .filter(m -> userTeamMembershipRepository.findByTeamCrbtId(m.getTeam().getCrbtId()).stream()
                    .anyMatch(member -> member.getUser().isFinancialAdvisor()))
            .collect(Collectors.groupingBy(m -> m.getTeam().getTeamType()));

        if (teamsByType.isEmpty()) return null;

        List<UserTeamMembership> finalTeams = new ArrayList<>();
        if (teamsByType.containsKey("VTM")) finalTeams.addAll(teamsByType.get("VTM"));
        if (teamsByType.containsKey("HTM")) finalTeams.addAll(teamsByType.get("HTM"));
        if (teamsByType.containsKey("SFA")) finalTeams.addAll(teamsByType.get("SFA"));

        if (finalTeams.isEmpty()) return null;

        String dynamicNamePart = finalTeams.stream()
            .map(m -> m.getTeam().getTeamName())
            .sorted()
            .collect(Collectors.joining("_"))
            .replaceAll("[ ./]", "_");

        String vpName = "Vis_" + dynamicNamePart;
        Set<String> groups = new HashSet<>();
        groups.add(dynamicNamePart);
        groups.add(user.getCountry() + " Field Submitters");

        return new CalculatedResult(vpName, groups);
    }

    private record CalculatedResult(String visibilityProfileName, Set<String> groups) {}
}
