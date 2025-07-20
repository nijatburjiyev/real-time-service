package com.edwardjones.cre.client;

import com.edwardjones.cre.model.domain.CrbtTeam;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class CrbtApiClient {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Data structure that matches the actual CRBT API response format
     */
    public static class CrbtApiTeamResponse {
        public String teamName;
        public Integer crbtID;
        public String ownerFaNo;
        public String ownerBrNo;
        public String tmBegDa;
        public String tmEndDa;
        public String teamTyCd;
        public String longTeamName;
        public String teamDisplayNameTypeCd;
        public String uidCd;
        public List<CrbtApiMember> memberList;

        public static class CrbtApiMember {
            public String ownerFaNo;
            public String ownerBrNo;
            public String ownerFaName;
            public String teamBegDa;
            public String teamEndDa;
            public String mbrRoleCd;
            public Integer crbtId;
            public String teamTyCd;
            public String mbrJorP;      // Username (p/j number)
            public String mbrEmplId;    // Employee ID
            public String mbrFaNo;      // FA Number
            public String mbrName;
            public String mbrBrNo;
            public String mbrBegDa;
            public String mbrEndDa;
            public Boolean active;
            public String mbrCreUt;
            public String uidCd;
        }
    }

    /**
     * Enhanced mock implementation that loads realistic CRBT teams from JSON file
     * and converts them to CrbtTeam entities matching actual CRBT API structure.
     */
    public List<CrbtTeam> fetchAllTeams() {
        log.info("MOCK - Fetching all teams from CRBT API...");

        try {
            // Try to load from new format JSON file first
            return loadTeamsFromNewFormatJsonFile();
        } catch (Exception e) {
            log.warn("Could not load teams from new format JSON file, trying old format: {}", e.getMessage());
            try {
                return loadTeamsFromJsonFile();
            } catch (Exception e2) {
                log.warn("Could not load teams from JSON files, falling back to hardcoded data: {}", e2.getMessage());
                return createHardcodedMockTeams();
            }
        }
    }

    /**
     * Fetch teams with member information for bootstrap process
     */
    public List<CrbtApiTeamResponse> fetchAllTeamsWithMembers() {
        log.info("MOCK - Fetching all teams with member details from CRBT API...");

        try {
            // Load from the new realistic format
            return loadTeamsWithMembersFromJsonFile();
        } catch (Exception e) {
            log.warn("Could not load teams with members from JSON file, falling back to hardcoded data: {}", e.getMessage());
            return createHardcodedTeamsWithMembers();
        }
    }

    /**
     * Load teams from the new realistic CRBT API format
     */
    private List<CrbtApiTeamResponse> loadTeamsWithMembersFromJsonFile() throws IOException {
        log.info("Loading teams with members from final-crbt-api-response.json file");

        ClassPathResource resource = new ClassPathResource("test-data/final-crbt-api-response.json");
        List<CrbtApiTeamResponse> teamResponses = objectMapper.readValue(
            resource.getInputStream(),
            new TypeReference<List<CrbtApiTeamResponse>>() {}
        );

        log.info("MOCK - Loaded {} teams with members from JSON file", teamResponses.size());
        return teamResponses;
    }

    /**
     * Convert new format to CrbtTeam entities
     */
    private List<CrbtTeam> loadTeamsFromNewFormatJsonFile() throws IOException {
        List<CrbtApiTeamResponse> teamResponses = loadTeamsWithMembersFromJsonFile();

        List<CrbtTeam> teams = new ArrayList<>();
        for (CrbtApiTeamResponse response : teamResponses) {
            CrbtTeam team = new CrbtTeam();
            team.setCrbtId(response.crbtID);
            team.setTeamName(response.teamName);
            team.setTeamType(response.teamTyCd);
            team.setActive(true); // Assume active if returned by API
            teams.add(team);

            log.debug("Converted CRBT team: {} - {} ({})",
                    team.getCrbtId(), team.getTeamName(), team.getTeamType());
        }

        return teams;
    }

    /**
     * Load teams from test JSON file that matches old CRBT API structure (fallback)
     */
    private List<CrbtTeam> loadTeamsFromJsonFile() throws IOException {
        log.info("Loading teams from test-crbt-teams.json file (old format)");

        ClassPathResource resource = new ClassPathResource("test-data/test-crbt-teams.json");
        List<Map<String, Object>> crbtTeamData = objectMapper.readValue(
            resource.getInputStream(),
            new TypeReference<List<Map<String, Object>>>() {}
        );

        List<CrbtTeam> teams = new ArrayList<>();

        for (Map<String, Object> crbtData : crbtTeamData) {
            CrbtTeam team = convertCrbtDataToTeam(crbtData);
            teams.add(team);
        }

        log.info("MOCK - Generated {} teams from old format JSON file", teams.size());
        return teams;
    }

    /**
     * Convert CRBT JSON structure to CrbtTeam entity (old format)
     */
    private CrbtTeam convertCrbtDataToTeam(Map<String, Object> crbtData) {
        CrbtTeam team = new CrbtTeam();

        team.setCrbtId((Integer) crbtData.get("crbtId"));
        team.setTeamName((String) crbtData.get("crbtNa"));
        team.setTeamType((String) crbtData.get("crbtTeamTyCd"));
        team.setActive((Boolean) crbtData.getOrDefault("isActive",
            crbtData.get("effEndDa") == null)); // Active if no end date

        return team;
    }

    /**
     * Create hardcoded teams with members for testing
     */
    private List<CrbtApiTeamResponse> createHardcodedTeamsWithMembers() {
        List<CrbtApiTeamResponse> teams = new ArrayList<>();

        // ACM Team - John Frank / Kevin Florer
        CrbtApiTeamResponse acmTeam = new CrbtApiTeamResponse();
        acmTeam.crbtID = 312595;
        acmTeam.teamName = "JOHN FRANK/ KEVIN FLORER";
        acmTeam.longTeamName = "John Frank / Kevin Florer Advisory Team";
        acmTeam.teamTyCd = "ACM";
        acmTeam.ownerFaNo = "903422";
        acmTeam.ownerBrNo = "123";
        acmTeam.tmBegDa = "2021-01-01";
        acmTeam.tmEndDa = "2023-12-31";
        acmTeam.memberList = new ArrayList<>();

        CrbtApiTeamResponse.CrbtApiMember johnFrank = new CrbtApiTeamResponse.CrbtApiMember();
        johnFrank.mbrJorP = "p300001";
        johnFrank.mbrEmplId = "0104553";
        johnFrank.mbrFaNo = "903422";
        johnFrank.mbrRoleCd = "FA";
        johnFrank.ownerFaNo = "903422";
        johnFrank.ownerBrNo = "123";
        johnFrank.ownerFaName = "John Frank";
        johnFrank.teamBegDa = "2021-01-01";
        johnFrank.teamEndDa = "2023-12-31";
        acmTeam.memberList.add(johnFrank);

        CrbtApiTeamResponse.CrbtApiMember kevinFlorer = new CrbtApiTeamResponse.CrbtApiMember();
        kevinFlorer.mbrJorP = "p300002";
        kevinFlorer.mbrEmplId = "0104554";
        kevinFlorer.mbrFaNo = "903422";
        kevinFlorer.mbrRoleCd = "BOA";
        kevinFlorer.ownerFaNo = "903422";
        kevinFlorer.ownerBrNo = "123";
        kevinFlorer.ownerFaName = "Kevin Florer";
        kevinFlorer.teamBegDa = "2021-01-01";
        kevinFlorer.teamEndDa = "2023-12-31";
        acmTeam.memberList.add(kevinFlorer);

        teams.add(acmTeam);

        // VTM Team - Maria Garcia
        CrbtApiTeamResponse vtmTeam = new CrbtApiTeamResponse();
        vtmTeam.crbtID = 450112;
        vtmTeam.teamName = "MARIA GARCIA VTM";
        vtmTeam.longTeamName = "Maria Garcia Virtual Team";
        vtmTeam.teamTyCd = "VTM";
        vtmTeam.ownerFaNo = "76543";
        vtmTeam.ownerBrNo = "456";
        vtmTeam.tmBegDa = "2021-01-01";
        vtmTeam.tmEndDa = "2023-12-31";
        vtmTeam.memberList = new ArrayList<>();

        CrbtApiTeamResponse.CrbtApiMember mariaGarcia = new CrbtApiTeamResponse.CrbtApiMember();
        mariaGarcia.mbrJorP = "j050001";
        mariaGarcia.mbrEmplId = "0076543";
        mariaGarcia.mbrFaNo = "76543";
        mariaGarcia.mbrRoleCd = "LEAD";
        mariaGarcia.ownerFaNo = "76543";
        mariaGarcia.ownerBrNo = "456";
        mariaGarcia.ownerFaName = "Maria Garcia";
        mariaGarcia.teamBegDa = "2021-01-01";
        mariaGarcia.teamEndDa = "2023-12-31";
        vtmTeam.memberList.add(mariaGarcia);

        // Add John Frank to VTM team as well (multi-team membership)
        CrbtApiTeamResponse.CrbtApiMember johnInVtm = new CrbtApiTeamResponse.CrbtApiMember();
        johnInVtm.mbrJorP = "p300001";
        johnInVtm.mbrEmplId = "0104553";
        johnInVtm.mbrFaNo = "903422";
        johnInVtm.mbrRoleCd = "FA";
        johnInVtm.ownerFaNo = "903422";
        johnInVtm.ownerBrNo = "123";
        johnInVtm.ownerFaName = "John Frank";
        johnInVtm.teamBegDa = "2021-01-01";
        johnInVtm.teamEndDa = "2023-12-31";
        vtmTeam.memberList.add(johnInVtm);

        // Add Kevin to VTM team as well
        CrbtApiTeamResponse.CrbtApiMember kevinInVtm = new CrbtApiTeamResponse.CrbtApiMember();
        kevinInVtm.mbrJorP = "p300002";
        kevinInVtm.mbrEmplId = "0104554";
        kevinInVtm.mbrFaNo = "903422";
        kevinInVtm.mbrRoleCd = "BOA";
        kevinInVtm.ownerFaNo = "903422";
        kevinInVtm.ownerBrNo = "123";
        kevinInVtm.ownerFaName = "Kevin Florer";
        kevinInVtm.teamBegDa = "2021-01-01";
        kevinInVtm.teamEndDa = "2023-12-31";
        vtmTeam.memberList.add(kevinInVtm);

        teams.add(vtmTeam);

        // SFA Team
        CrbtApiTeamResponse sfaTeam = new CrbtApiTeamResponse();
        sfaTeam.crbtID = 789555;
        sfaTeam.teamName = "TEXAS REGIONAL SFA";
        sfaTeam.longTeamName = "Texas Regional Senior Financial Advisor Group";
        sfaTeam.teamTyCd = "SFA";
        sfaTeam.ownerFaNo = "903422";
        sfaTeam.ownerBrNo = "789";
        sfaTeam.tmBegDa = "2021-01-01";
        sfaTeam.tmEndDa = "2023-12-31";
        sfaTeam.memberList = new ArrayList<>();

        CrbtApiTeamResponse.CrbtApiMember johnAsSfa = new CrbtApiTeamResponse.CrbtApiMember();
        johnAsSfa.mbrJorP = "p300001";
        johnAsSfa.mbrEmplId = "0104553";
        johnAsSfa.mbrFaNo = "903422";
        johnAsSfa.mbrRoleCd = "SFA";
        johnAsSfa.ownerFaNo = "903422";
        johnAsSfa.ownerBrNo = "789";
        johnAsSfa.ownerFaName = "John Frank";
        johnAsSfa.teamBegDa = "2021-01-01";
        johnAsSfa.teamEndDa = "2023-12-31";
        sfaTeam.memberList.add(johnAsSfa);

        teams.add(sfaTeam);

        log.info("MOCK - Generated {} hardcoded teams with members", teams.size());
        return teams;
    }

    /**
     * Fallback hardcoded data (updated to match realistic structure)
     */
    private List<CrbtTeam> createHardcodedMockTeams() {
        List<CrbtApiTeamResponse> teamsWithMembers = createHardcodedTeamsWithMembers();

        List<CrbtTeam> teams = new ArrayList<>();
        for (CrbtApiTeamResponse response : teamsWithMembers) {
            CrbtTeam team = new CrbtTeam();
            team.setCrbtId(response.crbtID);
            team.setTeamName(response.teamName);
            team.setTeamType(response.teamTyCd);
            team.setActive(true);
            teams.add(team);
        }

        log.info("MOCK - Generated {} hardcoded test teams", teams.size());
        return teams;
    }
}
