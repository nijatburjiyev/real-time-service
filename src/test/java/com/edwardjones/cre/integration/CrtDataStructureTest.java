package com.edwardjones.cre.integration;

import com.edwardjones.cre.client.CrbtApiClient;
import com.edwardjones.cre.model.dto.CrtChangeEvent;
import com.edwardjones.cre.service.realtime.ChangeEventProcessor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
public class CrtDataStructureTest {

    private static final Logger log = LoggerFactory.getLogger(CrtDataStructureTest.class);

    @Autowired
    private CrbtApiClient crbtApiClient;

    @Autowired
    private ChangeEventProcessor changeEventProcessor;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    public void testCrbtApiResponseStructure() throws IOException {
        log.info("Testing CRBT API response structure with new format");

        // Load and validate the new API response structure
        List<CrbtApiClient.CrbtApiTeamResponse> teams = crbtApiClient.fetchAllTeamsWithMembers();

        assertNotNull(teams, "Teams should not be null");
        assertTrue(teams.size() >= 3, "Should have at least 3 teams (VTM, SFA, HTM)");

        // Validate each team type is present
        boolean hasVtm = false, hasSfa = false, hasHtm = false;

        for (CrbtApiClient.CrbtApiTeamResponse team : teams) {
            assertNotNull(team.crbtID, "Team ID should not be null");
            assertNotNull(team.teamName, "Team name should not be null");
            assertNotNull(team.teamTyCd, "Team type should not be null");
            assertNotNull(team.memberList, "Member list should not be null");

            // Check for our target team types
            if ("VTM".equals(team.teamTyCd)) {
                hasVtm = true;
                log.info("Found VTM team: {} with {} members", team.teamName, team.memberList.size());
            } else if ("SFA".equals(team.teamTyCd)) {
                hasSfa = true;
                log.info("Found SFA team: {} with {} members", team.teamName, team.memberList.size());
            } else if ("HTM".equals(team.teamTyCd)) {
                hasHtm = true;
                log.info("Found HTM team: {} with {} members", team.teamName, team.memberList.size());
            }

            // Validate member structure
            for (CrbtApiClient.CrbtApiTeamResponse.CrbtApiMember member : team.memberList) {
                assertNotNull(member.mbrJorP, "Member PJ number should not be null");
                assertNotNull(member.mbrEmplId, "Member employee ID should not be null");
                assertNotNull(member.mbrRoleCd, "Member role should not be null");
                log.debug("Member: {} - {} ({})", member.mbrJorP, member.mbrName, member.mbrRoleCd);
            }
        }

        assertTrue(hasVtm, "Should have at least one VTM team");
        assertTrue(hasSfa, "Should have at least one SFA team");
        assertTrue(hasHtm, "Should have at least one HTM team");
    }

    @Test
    public void testCrtKafkaEventStructure() throws IOException {
        log.info("Testing CRT Kafka event structure with new format");

        // Load the CRT Kafka events
        ClassPathResource resource = new ClassPathResource("test-data/final-crt-kafka-events.json");
        List<Map<String, Object>> eventData = objectMapper.readValue(
            resource.getInputStream(),
            new TypeReference<List<Map<String, Object>>>() {}
        );

        assertNotNull(eventData, "Event data should not be null");
        assertTrue(eventData.size() >= 5, "Should have multiple test scenarios");

        int vtmEvents = 0, sfaEvents = 0, htmEvents = 0;

        for (Map<String, Object> eventWrapper : eventData) {
            String scenario = (String) eventWrapper.get("scenario");
            Map<String, Object> event = (Map<String, Object>) eventWrapper.get("event");

            assertNotNull(scenario, "Scenario should not be null");
            assertNotNull(event, "Event should not be null");

            // Convert to CrtChangeEvent and validate
            CrtChangeEvent crtEvent = objectMapper.convertValue(event, CrtChangeEvent.class);

            assertNotNull(crtEvent.getCrbtId(), "CRBT ID should not be null");
            assertNotNull(crtEvent.getCrbtName(), "CRBT name should not be null");
            assertNotNull(crtEvent.getTeamType(), "Team type should not be null");
            assertNotNull(crtEvent.getMembers(), "Members should not be null");
            assertNotNull(crtEvent.getMembers().getEmployeeId(), "Member employee ID should not be null");
            assertNotNull(crtEvent.getMembers().getRole(), "Member role should not be null");

            // Count events by team type
            String teamType = crtEvent.getTeamType();
            if ("VTM".equals(teamType)) {
                vtmEvents++;
            } else if ("SFA".equals(teamType)) {
                sfaEvents++;
            } else if ("HTM".equals(teamType)) {
                htmEvents++;
            }

            log.info("Scenario: {} - Team: {} ({}) - Member: {} ({})",
                    scenario, crtEvent.getCrbtName(), teamType,
                    crtEvent.getMembers().getEmployeeId(), crtEvent.getMembers().getRole());

            // Test event processing capabilities
            assertDoesNotThrow(() -> {
                boolean isManagerial = crtEvent.isManagerialChange();
                boolean isMemberLeaving = crtEvent.isMemberLeaving();
                boolean isTeamDeactivated = crtEvent.isTeamDeactivated();

                log.debug("Event analysis - Managerial: {}, Member leaving: {}, Team deactivated: {}",
                         isManagerial, isMemberLeaving, isTeamDeactivated);
            }, "Event analysis methods should not throw exceptions");
        }

        assertTrue(vtmEvents > 0, "Should have VTM events");
        assertTrue(sfaEvents > 0, "Should have SFA events");
        assertTrue(htmEvents > 0, "Should have HTM events");
    }

    @Test
    public void testCrtEventProcessing() throws IOException {
        log.info("Testing CRT event processing with new scenarios");

        // Load a sample event and process it
        ClassPathResource resource = new ClassPathResource("test-data/final-crt-kafka-events.json");
        List<Map<String, Object>> eventData = objectMapper.readValue(
            resource.getInputStream(),
            new TypeReference<List<Map<String, Object>>>() {}
        );

        Map<String, Object> firstEventWrapper = eventData.get(0);
        Map<String, Object> firstEvent = (Map<String, Object>) firstEventWrapper.get("event");
        CrtChangeEvent crtEvent = objectMapper.convertValue(firstEvent, CrtChangeEvent.class);

        // Test that the event processor can handle the new structure
        assertDoesNotThrow(() -> {
            changeEventProcessor.processCrtChange(crtEvent);
            log.info("Successfully processed CRT event for team {} with member {}",
                    crtEvent.getCrbtId(), crtEvent.getMembers().getEmployeeId());
        }, "CRT event processing should not fail");
    }

    @Test
    public void testTeamTypeFiltering() {
        log.info("Testing team type filtering for VTM, SFA, HTM");

        // Create test events for each team type
        CrtChangeEvent vtmEvent = createTestEvent("VTM", "BOA");
        CrtChangeEvent sfaEvent = createTestEvent("SFA", "LEAD");
        CrtChangeEvent htmEvent = createTestEvent("HTM", "BOA");

        // All should be considered managerial changes
        assertTrue(vtmEvent.isManagerialChange(), "VTM events should be considered managerial");
        assertTrue(sfaEvent.isManagerialChange(), "SFA events should be considered managerial");
        assertTrue(htmEvent.isManagerialChange(), "HTM events should be considered managerial");

        log.info("All target team types (VTM, SFA, HTM) correctly identified as managerial changes");
    }

    private CrtChangeEvent createTestEvent(String teamType, String role) {
        CrtChangeEvent event = new CrtChangeEvent();
        event.setCrbtId(12345);
        event.setCrbtName("Test Team");
        event.setTeamType(teamType);

        CrtChangeEvent.CrtMemberChange member = new CrtChangeEvent.CrtMemberChange();
        member.setEmployeeId("0123456");
        member.setRole(role);
        event.setMembers(member);

        return event;
    }
}
