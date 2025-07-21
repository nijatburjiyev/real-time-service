package com.edwardjones.cre.integration;

import com.edwardjones.cre.client.VendorApiClient;
import com.edwardjones.cre.model.domain.AppUser;
import com.edwardjones.cre.model.dto.AdChangeEvent;
import com.edwardjones.cre.repository.AppUserRepository;
import com.edwardjones.cre.service.logic.ComplianceLogicService;
import com.edwardjones.cre.service.realtime.ChangeEventProcessor;
import com.edwardjones.cre.service.realtime.ComplianceKafkaListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

/**
 * Comprehensive integration test that validates the complete compliance sync workflow.
 * Each test runs against a fresh, fully bootstrapped database thanks to @DirtiesContext.
 *
 * Key improvements:
 * - No manual bootstrap calls - Spring lifecycle handles it automatically
 * - Each test gets a clean slate with @DirtiesContext
 * - Verifies vendor API calls for real-time events
 * - Focuses on business outcomes rather than implementation details
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
public class UnifiedComplianceIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(UnifiedComplianceIntegrationTest.class);

    @Autowired private ComplianceLogicService complianceLogicService;
    @Autowired private ChangeEventProcessor changeEventProcessor;
    @Autowired private AppUserRepository appUserRepository;

    @MockitoBean private ComplianceKafkaListener mockKafkaListener;
    @MockitoBean private VendorApiClient mockVendorApiClient;

    @Test
    @DisplayName("Business Case 1: Simple Branch User (BR)")
    @Transactional
    void testSimpleBranchUser_BR() {
        AppUser result = complianceLogicService.calculateConfigurationForUser("p300002"); // Kevin Florer
        assertEquals("Vis-US-BR", result.getCalculatedVisibilityProfile());
        assertTrue(result.getCalculatedGroups().contains("US Field Submitters"));
        log.info("✅ testSimpleBranchUser_BR PASSED");
    }

    @Test
    @DisplayName("Business Case 2: Home Office User (HO)")
    @Transactional
    void testSimpleHomeOfficeUser_HO() {
        AppUser result = complianceLogicService.calculateConfigurationForUser("p200001"); // David Chen
        assertEquals("Vis-US-HO", result.getCalculatedVisibilityProfile());
        assertTrue(result.getCalculatedGroups().contains("US Home Office Submitters"));
        log.info("✅ testSimpleHomeOfficeUser_HO PASSED");
    }

    @Test
    @DisplayName("Business Case 3: Home Office Leader (HO_LEADER)")
    @Transactional
    void testHomeOfficeLeader_HO_LEADER() {
        AppUser result = complianceLogicService.calculateConfigurationForUser("p100001"); // Katherine Powell
        assertEquals("Vis_HO_Katherine_Powell_(p100001)", result.getCalculatedVisibilityProfile());
        assertTrue(result.getCalculatedGroups().contains("US_HO_Katherine_Powell_(p100001)"));
        log.info("✅ testHomeOfficeLeader_HO_LEADER PASSED");
    }

    @Test
    @DisplayName("Business Case 4: Branch Leader with Team (BR_TEAM)")
    @Transactional
    void testBranchLeaderWithTeam_BR_TEAM() {
        AppUser result = complianceLogicService.calculateConfigurationForUser("j050001"); // Maria Garcia
        String vpProfile = result.getCalculatedVisibilityProfile();
        assertTrue(vpProfile.contains("JOHN_FRANK/_KEVIN_FLORER") && vpProfile.contains("VTM"));
        assertTrue(result.getCalculatedGroups().contains("US Field Submitters"));
        log.info("✅ testBranchLeaderWithTeam_BR_TEAM PASSED");
    }

    @Test
    @DisplayName("Business Case 5: Canadian User")
    @Transactional
    void testCanadianUser_CountrySpecific() {
        AppUser result = complianceLogicService.calculateConfigurationForUser("p400001"); // Aisha Khan
        assertEquals("Vis-CA-HO", result.getCalculatedVisibilityProfile());
        assertTrue(result.getCalculatedGroups().contains("CAN Home Office Submitters"));
        log.info("✅ testCanadianUser_CountrySpecific PASSED");
    }

    @Test
    @DisplayName("Business Case 6: Hybrid User (HOBR)")
    @Transactional
    void testHybridUser_HOBR() {
        // GIVEN: A user with a Branch title but an HO distinguished name is created
        AppUser hybridUser = new AppUser();
        hybridUser.setUsername("hybrid01");
        hybridUser.setEmployeeId("999001");
        hybridUser.setFirstName("Hybrid");
        hybridUser.setLastName("User");
        hybridUser.setTitle("Remote Branch On-Caller"); // Title implies Branch role
        hybridUser.setDistinguishedName("CN=hybrid01,OU=Home Office,OU=US,..."); // DN implies HO location
        hybridUser.setCountry("US");
        appUserRepository.save(hybridUser);

        // WHEN: We calculate their configuration
        AppUser result = complianceLogicService.calculateConfigurationForUser("hybrid01");

        // THEN: They should get a combined profile and both submitter groups
        assertEquals("Vis-US-HO-BR", result.getCalculatedVisibilityProfile());
        assertTrue(result.getCalculatedGroups().containsAll(Set.of("US Home Office Submitters", "US Field Submitters")));
        log.info("✅ testHybridUser_HOBR PASSED");
    }

    @Test
    @DisplayName("Real-time Event: Manager Change Triggers Update to Vendor")
    @Transactional
    void testRealTime_ManagerChange() {
        // GIVEN: A user, David Chen
        String username = "p200001";

        // WHEN: An AD event changes his manager
        AdChangeEvent event = new AdChangeEvent();
        event.setPjNumber(username);
        event.setChangeType("DataChange");
        event.setProperty("Manager");
        event.setNewValue("CN=j050001,OU=Leaders,..."); // New manager is Maria Garcia

        changeEventProcessor.processAdChange(event);

        // THEN: The Vendor API should have been called with the updated user data.
        ArgumentCaptor<AppUser> userCaptor = ArgumentCaptor.forClass(AppUser.class);
        verify(mockVendorApiClient).updateUser(userCaptor.capture());

        // Assert that the correct user with the correct new manager was sent
        assertEquals(username, userCaptor.getValue().getUsername());
        AppUser userInDb = appUserRepository.findById(username).orElseThrow();
        assertEquals("j050001", userInDb.getManagerUsername());

        log.info("✅ testRealTime_ManagerChange PASSED");
    }

    @Test
    @DisplayName("Real-time Event: Title Change for Leader Affects Direct Reports")
    @Transactional
    void testRealTime_LeaderTitleChangeImpactsDirectReports() {
        // GIVEN: Katherine Powell is a leader with direct reports
        String leaderUsername = "p100001";

        // WHEN: Her title changes
        AdChangeEvent event = new AdChangeEvent();
        event.setPjNumber(leaderUsername);
        event.setChangeType("DataChange");
        event.setProperty("Title");
        event.setNewValue("Senior Vice President - Compliance");

        changeEventProcessor.processAdChange(event);

        // THEN: The vendor API should be called for Katherine and her direct reports (5 total calls)
        ArgumentCaptor<AppUser> userCaptor = ArgumentCaptor.forClass(AppUser.class);
        verify(mockVendorApiClient, times(5)).updateUser(userCaptor.capture());

        // Verify Katherine's record was updated (she should be one of the captured users)
        boolean katherineUpdated = userCaptor.getAllValues().stream()
                .anyMatch(user -> leaderUsername.equals(user.getUsername()));
        assertTrue(katherineUpdated, "Katherine should be updated when her title changes");

        log.info("✅ testRealTime_LeaderTitleChangeImpactsDirectReports PASSED");
    }

    @Test
    @DisplayName("Real-time Event: User Deactivation")
    @Transactional
    void testRealTime_UserDeactivation() {
        // GIVEN: An active user
        String username = "p300002";
        AppUser userBefore = appUserRepository.findById(username).orElseThrow();
        assertTrue(userBefore.isActive());

        // WHEN: They are deactivated via AD event
        AdChangeEvent event = new AdChangeEvent();
        event.setPjNumber(username);
        event.setChangeType(AdChangeEvent.CHANGE_TYPE_DATA_CHANGE);
        event.setProperty(AdChangeEvent.PROPERTY_ENABLED);
        event.setNewValue("false");

        changeEventProcessor.processAdChange(event);

        // THEN: The user is marked inactive but NO vendor call is made
        verify(mockVendorApiClient, never()).updateUser(any(AppUser.class));

        AppUser userAfter = appUserRepository.findById(username).orElseThrow();
        assertNotNull(userAfter, "User should still exist in database");
        assertFalse(userAfter.isActive(), "User should be marked as inactive");

        // Note: This system treats "Enabled" changes as non-impactful, which is correct behavior
        // No vendor API call is expected for this type of change

        log.info("✅ testRealTime_UserDeactivation PASSED");
    }

    @Test
    @DisplayName("Edge Case: Unknown User Event Handling")
    @Transactional
    void testEdgeCase_UnknownUserEvent() {
        // GIVEN: An AD event for a user not in our system
        AdChangeEvent event = new AdChangeEvent();
        event.setPjNumber("unknown_user");
        event.setChangeType("DataChange");
        event.setProperty("Title");
        event.setNewValue("Some New Title");

        // WHEN: We process the event
        // THEN: It should not crash the system (graceful handling)
        assertDoesNotThrow(() -> changeEventProcessor.processAdChange(event));

        log.info("✅ testEdgeCase_UnknownUserEvent PASSED");
    }

    @Test
    @DisplayName("Bootstrap Verification: Test Data Integrity")
    @Transactional
    void testBootstrap_DataIntegrity() {
        // GIVEN: The application has started and bootstrap has run
        // THEN: Key test users should exist with correct relationships

        // Verify Katherine Powell exists and has direct reports
        AppUser katherine = appUserRepository.findById("p100001").orElseThrow();
        assertEquals("Katherine", katherine.getFirstName());
        assertEquals("Powell", katherine.getLastName());
        assertTrue(katherine.isActive());

        // Verify David Chen exists and has Katherine as manager
        AppUser david = appUserRepository.findById("p200001").orElseThrow();
        assertEquals("p100001", david.getManagerUsername());

        // Verify Maria Garcia exists and has direct reports
        AppUser maria = appUserRepository.findById("j050001").orElseThrow();
        assertEquals("Maria", maria.getFirstName());
        assertNotNull(maria.getDirectReports());

        // Verify Canadian user exists
        AppUser aisha = appUserRepository.findById("p400001").orElseThrow();
        assertEquals("CA", aisha.getCountry());

        log.info("✅ testBootstrap_DataIntegrity PASSED");
    }
}
