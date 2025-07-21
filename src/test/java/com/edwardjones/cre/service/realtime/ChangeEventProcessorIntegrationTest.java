package com.edwardjones.cre.service.realtime;

import com.edwardjones.cre.ComplianceSyncApplication;
import com.edwardjones.cre.business.ComplianceLogicService;
import com.edwardjones.cre.client.VendorApiClient;
import com.edwardjones.cre.model.domain.AppUser;
import com.edwardjones.cre.model.domain.CrbtTeam;
import com.edwardjones.cre.model.domain.UserTeamMembership;
import com.edwardjones.cre.model.dto.AdChangeEvent;
import com.edwardjones.cre.model.dto.CrtChangeEvent;
import com.edwardjones.cre.repository.AppUserRepository;
import com.edwardjones.cre.repository.CrbtTeamRepository;
import com.edwardjones.cre.repository.UserTeamMembershipRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(classes = ComplianceSyncApplication.class)
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("ChangeEventProcessor Integration Tests")
class ChangeEventProcessorIntegrationTest {

    @Autowired
    private ChangeEventProcessor changeEventProcessor;

    @Autowired
    private ComplianceKafkaListener kafkaListener;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private CrbtTeamRepository crbtTeamRepository;

    @Autowired
    private UserTeamMembershipRepository userTeamMembershipRepository;

    @MockBean
    private VendorApiClient vendorApiClient;

    @Autowired
    private ComplianceLogicService complianceLogicService;

    @Autowired
    private ObjectMapper objectMapper;

    private List<AdChangeEvent> testAdEvents;
    private List<CrtChangeEvent> testCrtEvents;

    @BeforeEach
    void setUp() throws IOException {
        // Load test data from JSON files
        loadTestData();

        // Reset vendor API mock
        reset(vendorApiClient);
    }

    @Test
    @Order(1)
    @DisplayName("Bootstrap: Should initialize all required data in database")
    void shouldInitializeAllRequiredDataInDatabase() {
        // Verify users are loaded
        List<AppUser> users = appUserRepository.findAll();
        assertThat(users).isNotEmpty();
        assertThat(users.size()).isGreaterThan(10); // Based on mock data

        // Verify teams are loaded
        List<CrbtTeam> teams = crbtTeamRepository.findAll();
        assertThat(teams).isNotEmpty();
        assertThat(teams.size()).isGreaterThan(5); // Based on mock data

        // Verify team memberships are created
        List<UserTeamMembership> memberships = userTeamMembershipRepository.findAll();
        assertThat(memberships).isNotEmpty();

        // Verify specific test users exist for our scenarios
        Optional<AppUser> testUser1 = appUserRepository.findById("p007435");
        assertThat(testUser1).isPresent();
        assertThat(testUser1.get().getEmployeeId()).isEqualTo("123456"); // Will be updated by AD event

        Optional<AppUser> testUser2 = appUserRepository.findById("p231756");
        assertThat(testUser2).isPresent();
        assertThat(testUser2.get().getCountry()).isEqualTo("YY"); // Will be updated by AD event

        // Verify specific test teams exist
        Optional<CrbtTeam> vtmTeam = crbtTeamRepository.findById(312595);
        assertThat(vtmTeam).isPresent();
        assertThat(vtmTeam.get().getTeamType()).isEqualTo("VTM");

        Optional<CrbtTeam> sfaTeam = crbtTeamRepository.findById(450112);
        assertThat(sfaTeam).isPresent();
        assertThat(sfaTeam.get().getTeamType()).isEqualTo("SFA");
    }

    @Test
    @Order(2)
    @DisplayName("Kafka Listener: Should begin listening to AD changes topic")
    @Transactional
    void shouldBeginListeningToAdChangesTopic() {
        // Given - First AD event: Employee ID change for p007435
        AdChangeEvent employeeIdChange = testAdEvents.stream()
                .filter(event -> "p007435".equals(event.getPjNumber()) && "ej-IRNumber".equals(event.getProperty()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Test data missing required AD event"));

        // When - Simulate Kafka message consumption
        kafkaListener.consumeAdChanges(employeeIdChange);

        // Then - Verify the change was processed
        Optional<AppUser> updatedUser = appUserRepository.findById("p007435");
        assertThat(updatedUser).isPresent();
        assertThat(updatedUser.get().getEmployeeId()).isEqualTo("420659");

        // Employee ID changes are not impactful, so no vendor API call expected
        verifyNoInteractions(vendorApiClient);
    }

    @Test
    @Order(3)
    @DisplayName("Kafka Listener: Should begin listening to CRT changes topic")
    @Transactional
    void shouldBeginListeningToCrtChangesTopic() {
        // Given - First CRT event: New member joins VTM team
        CrtChangeEvent memberJoinsEvent = findCrtEventByScenario("vtm_member_joins");

        // Ensure the user exists with the correct employee ID
        Optional<AppUser> memberUser = appUserRepository.findByEmployeeId("0246517");
        assertThat(memberUser).isPresent().withFailMessage("Test user with employee ID 0246517 should exist");

        // When - Simulate Kafka message consumption
        kafkaListener.consumeCrtChanges(memberJoinsEvent);

        // Then - Verify the membership was created
        Optional<UserTeamMembership> newMembership = userTeamMembershipRepository
                .findById(new com.edwardjones.cre.model.domain.UserTeamMembershipId(memberUser.get().getUsername(), 312595));
        assertThat(newMembership).isPresent();
        assertThat(newMembership.get().getMemberRole()).isEqualTo("BOA");

        // Verify vendor API was called for configuration update
        verify(vendorApiClient, times(1)).updateUser(any(AppUser.class));
    }

    @Test
    @Order(4)
    @DisplayName("AD Processing: Should process state change and trigger vendor update")
    @Transactional
    void shouldProcessStateChangeAndTriggerVendorUpdate() {
        // Given - State change event from test data
        AdChangeEvent stateChange = testAdEvents.stream()
                .filter(event -> "p231756".equals(event.getPjNumber()) && "State".equals(event.getProperty()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Test data missing state change event"));

        // Capture initial state
        Optional<AppUser> userBeforeChange = appUserRepository.findById("p231756");
        assertThat(userBeforeChange).isPresent();
        assertThat(userBeforeChange.get().getCountry()).isEqualTo("YY");

        // When - Process the change
        changeEventProcessor.processAdChange(stateChange);

        // Then - Verify state was updated
        Optional<AppUser> userAfterChange = appUserRepository.findById("p231756");
        assertThat(userAfterChange).isPresent();
        assertThat(userAfterChange.get().getCountry()).isEqualTo("TX");

        // Verify vendor API was called due to impactful change
        verify(vendorApiClient, atLeastOnce()).updateUser(any(AppUser.class));
    }

    @Test
    @Order(5)
    @DisplayName("AD Processing: Should process user termination correctly")
    @Transactional
    void shouldProcessUserTerminationCorrectly() {
        // Given - Termination event from test data
        AdChangeEvent terminationEvent = testAdEvents.stream()
                .filter(event -> "TerminatedUser".equals(event.getChangeType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Test data missing termination event"));

        String terminatedUserId = terminationEvent.getPjNumber();

        // Ensure user exists and is active
        Optional<AppUser> userBeforeTermination = appUserRepository.findById(terminatedUserId);
        assertThat(userBeforeTermination).isPresent();
        assertThat(userBeforeTermination.get().isActive()).isTrue();

        // When - Process termination
        changeEventProcessor.processAdChange(terminationEvent);

        // Then - Verify user was deactivated
        Optional<AppUser> userAfterTermination = appUserRepository.findById(terminatedUserId);
        assertThat(userAfterTermination).isPresent();
        assertThat(userAfterTermination.get().isActive()).isFalse();

        // Verify vendor API was called for final configuration
        verify(vendorApiClient, atLeastOnce()).updateUser(any(AppUser.class));
    }

    @Test
    @Order(6)
    @DisplayName("CRT Processing: Should process role change and update direct reports")
    @Transactional
    void shouldProcessRoleChangeAndUpdateDirectReports() {
        // Given - Role change to LEAD from test data
        CrtChangeEvent roleChangeEvent = findCrtEventByScenario("vtm_role_change");

        // Ensure the user exists
        Optional<AppUser> userForRoleChange = appUserRepository.findByEmployeeId("0198576");
        assertThat(userForRoleChange).isPresent().withFailMessage("Test user with employee ID 0198576 should exist");

        // Create or verify existing membership
        Optional<com.edwardjones.cre.model.domain.UserTeamMembership> existingMembership =
                userTeamMembershipRepository.findById(
                        new com.edwardjones.cre.model.domain.UserTeamMembershipId(userForRoleChange.get().getUsername(), 312595));

        if (existingMembership.isEmpty()) {
            // Create membership if it doesn't exist
            com.edwardjones.cre.model.domain.UserTeamMembership membership = new com.edwardjones.cre.model.domain.UserTeamMembership();
            membership.setId(new com.edwardjones.cre.model.domain.UserTeamMembershipId(userForRoleChange.get().getUsername(), 312595));
            membership.setUser(userForRoleChange.get());
            membership.setTeam(crbtTeamRepository.findById(312595).get());
            membership.setMemberRole("FA");
            userTeamMembershipRepository.save(membership);
        }

        // When - Process role change
        changeEventProcessor.processCrtChange(roleChangeEvent);

        // Then - Verify role was updated
        Optional<com.edwardjones.cre.model.domain.UserTeamMembership> updatedMembership =
                userTeamMembershipRepository.findById(
                        new com.edwardjones.cre.model.domain.UserTeamMembershipId(userForRoleChange.get().getUsername(), 312595));
        assertThat(updatedMembership).isPresent();
        assertThat(updatedMembership.get().getMemberRole()).isEqualTo("LEAD");

        // Verify vendor API was called (at least once for the user, possibly more for direct reports)
        verify(vendorApiClient, atLeastOnce()).updateUser(any(AppUser.class));
    }

    @Test
    @Order(7)
    @DisplayName("CRT Processing: Should process member leaving team")
    @Transactional
    void shouldProcessMemberLeavingTeam() {
        // Given - Member leaving event from test data
        CrtChangeEvent memberLeavesEvent = findCrtEventByScenario("sfa_member_leaves");

        // Ensure the user exists
        Optional<AppUser> leavingUser = appUserRepository.findByEmployeeId("0160002");
        assertThat(leavingUser).isPresent().withFailMessage("Test user with employee ID 0160002 should exist");

        // Create membership if it doesn't exist
        com.edwardjones.cre.model.domain.UserTeamMembershipId membershipId =
                new com.edwardjones.cre.model.domain.UserTeamMembershipId(leavingUser.get().getUsername(), 450112);

        Optional<com.edwardjones.cre.model.domain.UserTeamMembership> existingMembership =
                userTeamMembershipRepository.findById(membershipId);

        if (existingMembership.isEmpty()) {
            com.edwardjones.cre.model.domain.UserTeamMembership membership = new com.edwardjones.cre.model.domain.UserTeamMembership();
            membership.setId(membershipId);
            membership.setUser(leavingUser.get());
            membership.setTeam(crbtTeamRepository.findById(450112).get());
            membership.setMemberRole("BOA");
            userTeamMembershipRepository.save(membership);
        }

        // When - Process member leaving
        changeEventProcessor.processCrtChange(memberLeavesEvent);

        // Then - Verify membership was removed
        Optional<com.edwardjones.cre.model.domain.UserTeamMembership> membershipAfterLeaving =
                userTeamMembershipRepository.findById(membershipId);
        assertThat(membershipAfterLeaving).isEmpty();

        // Verify vendor API was called for configuration update
        verify(vendorApiClient, atLeastOnce()).updateUser(any(AppUser.class));
    }

    @Test
    @Order(8)
    @DisplayName("Complete Scenario: Should process multiple events and maintain data consistency")
    @Transactional
    void shouldProcessMultipleEventsAndMaintainDataConsistency() {
        // Given - Multiple events from test data
        int initialUserCount = (int) appUserRepository.count();
        int initialTeamCount = (int) crbtTeamRepository.count();
        int initialMembershipCount = (int) userTeamMembershipRepository.count();

        // Process first few AD events
        testAdEvents.stream()
                .filter(event -> "DataChange".equals(event.getChangeType()))
                .limit(3)
                .forEach(event -> changeEventProcessor.processAdChange(event));

        // Process first few CRT events
        testCrtEvents.stream()
                .limit(2)
                .forEach(event -> changeEventProcessor.processCrtChange(event));

        // Then - Verify data consistency
        assertThat(appUserRepository.count()).isEqualTo(initialUserCount); // No new users added
        assertThat(crbtTeamRepository.count()).isEqualTo(initialTeamCount); // No new teams added

        // Membership count may change due to joins/leaves
        long finalMembershipCount = userTeamMembershipRepository.count();
        assertThat(finalMembershipCount).isGreaterThanOrEqualTo(initialMembershipCount - 5); // Allow for some leaves

        // Verify vendor API was called multiple times
        verify(vendorApiClient, atLeast(3)).updateUser(any(AppUser.class));

        // Verify no orphaned data
        List<com.edwardjones.cre.model.domain.UserTeamMembership> allMemberships = userTeamMembershipRepository.findAll();
        for (com.edwardjones.cre.model.domain.UserTeamMembership membership : allMemberships) {
            assertThat(membership.getUser()).isNotNull();
            assertThat(membership.getTeam()).isNotNull();
            assertThat(appUserRepository.existsById(membership.getUser().getUsername())).isTrue();
            assertThat(crbtTeamRepository.existsById(membership.getTeam().getCrbtId())).isTrue();
        }
    }

    @Test
    @Order(9)
    @DisplayName("Error Handling: Should handle invalid events gracefully")
    void shouldHandleInvalidEventsGracefully() {
        // Given - Invalid AD event (non-existent user)
        AdChangeEvent invalidAdEvent = new AdChangeEvent();
        invalidAdEvent.setPjNumber("p999999");
        invalidAdEvent.setChangeType("DataChange");
        invalidAdEvent.setProperty("Title");
        invalidAdEvent.setNewValue("New Title");

        // Given - Invalid CRT event (non-existent team)
        CrtChangeEvent invalidCrtEvent = new CrtChangeEvent();
        invalidCrtEvent.setCrbtId(999999);
        CrtChangeEvent.CrtMemberChange member = new CrtChangeEvent.CrtMemberChange();
        member.setEmployeeId("0999999");
        member.setRole("BOA");
        invalidCrtEvent.setMembers(member);

        // When - Process invalid events (should not throw exceptions)
        Assertions.assertDoesNotThrow(() -> {
            changeEventProcessor.processAdChange(invalidAdEvent);
            changeEventProcessor.processCrtChange(invalidCrtEvent);
        });

        // Then - No vendor API calls should be made for invalid events
        verifyNoInteractions(vendorApiClient);
    }

    // Helper methods

    private void loadTestData() throws IOException {
        // Load AD change events
        ClassPathResource adEventsResource = new ClassPathResource("test-data/test-ad-change-events.json");
        testAdEvents = objectMapper.readValue(adEventsResource.getInputStream(), new TypeReference<List<AdChangeEvent>>() {});

        // Load CRT change events
        ClassPathResource crtEventsResource = new ClassPathResource("test-data/final-crt-kafka-events.json");
        List<TestCrtEventWrapper> crtEventWrappers = objectMapper.readValue(crtEventsResource.getInputStream(),
                new TypeReference<List<TestCrtEventWrapper>>() {});
        testCrtEvents = crtEventWrappers.stream()
                .map(wrapper -> wrapper.event)
                .toList();
    }

    private CrtChangeEvent findCrtEventByScenario(String scenario) {
        try {
            ClassPathResource crtEventsResource = new ClassPathResource("test-data/final-crt-kafka-events.json");
            List<TestCrtEventWrapper> crtEventWrappers = objectMapper.readValue(crtEventsResource.getInputStream(),
                    new TypeReference<List<TestCrtEventWrapper>>() {});

            return crtEventWrappers.stream()
                    .filter(wrapper -> scenario.equals(wrapper.scenario))
                    .map(wrapper -> wrapper.event)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Test data missing CRT event for scenario: " + scenario));
        } catch (IOException e) {
            throw new RuntimeException("Failed to load CRT test data", e);
        }
    }

    // Wrapper class to match the test data JSON structure
    private static class TestCrtEventWrapper {
        public String scenario;
        public String description;
        public CrtChangeEvent event;
    }
}
