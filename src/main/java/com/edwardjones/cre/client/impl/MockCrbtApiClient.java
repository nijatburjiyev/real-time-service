package com.edwardjones.cre.client.impl;

import com.edwardjones.cre.client.CrbtApiClient;
import com.edwardjones.cre.model.domain.CrbtTeam;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@Profile("test")
public class MockCrbtApiClient implements CrbtApiClient {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public List<CrbtTeam> fetchAllTeams() {
        log.info("MOCK - Fetching all teams from CRBT API...");
        try {
            return loadTeamsFromJsonFile();
        } catch (Exception e) {
            log.warn("Could not load teams from JSON file, falling back to hardcoded data: {}", e.getMessage());
            return createHardcodedMockTeams();
        }
    }

    @Override
    public List<CrbtApiTeamResponse> fetchAllTeamsWithMembers() {
        log.info("MOCK - Fetching all teams with member details from CRBT API...");
        try {
            return loadTeamsWithMembersFromJsonFile();
        } catch (Exception e) {
            log.warn("Could not load teams with members from JSON file, falling back to hardcoded data: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private List<CrbtTeam> loadTeamsFromJsonFile() throws IOException {
        List<CrbtApiTeamResponse> teamResponses = loadTeamsWithMembersFromJsonFile();
        List<CrbtTeam> teams = new ArrayList<>();

        for (CrbtApiTeamResponse response : teamResponses) {
            CrbtTeam team = new CrbtTeam();
            team.setCrbtId(response.crbtID);
            team.setTeamName(response.teamName);
            team.setTeamType(response.teamTyCd);
            team.setActive(true);
            teams.add(team);
        }
        return teams;
    }

    private List<CrbtApiTeamResponse> loadTeamsWithMembersFromJsonFile() throws IOException {
        ClassPathResource resource = new ClassPathResource("test-data/final-crbt-api-response.json");
        return objectMapper.readValue(
            resource.getInputStream(),
            new TypeReference<List<CrbtApiTeamResponse>>() {}
        );
    }

    private List<CrbtTeam> createHardcodedMockTeams() {
        List<CrbtTeam> teams = new ArrayList<>();

        CrbtTeam team1 = new CrbtTeam();
        team1.setCrbtId(312595);
        team1.setTeamName("JOHN FRANK/ KEVIN FLORER");
        team1.setTeamType("VTM");
        team1.setActive(true);
        teams.add(team1);

        return teams;
    }
}
