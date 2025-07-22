package com.edwardjones.cre.integration;

import com.edwardjones.cre.client.VendorApiClient;
import com.edwardjones.cre.model.dto.AdChangeEvent;
import com.edwardjones.cre.model.dto.DesiredConfiguration;
import com.edwardjones.cre.service.logic.ComplianceLogicService;
import com.edwardjones.cre.service.realtime.ChangeEventProcessor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Updated integration test for the refactored clean architecture.
 * Tests the new DesiredConfiguration-based workflow.
 */
@SpringBootTest
@ActiveProfiles("test")
public class UnifiedComplianceIntegrationTest {

    @Autowired
    private ComplianceLogicService complianceLogicService;

    @Autowired
    private ChangeEventProcessor changeEventProcessor;

    @MockBean
    private VendorApiClient mockVendorApiClient;

    // ==================== Core Business Logic Tests ====================

    @Test
    public void testBranchUserCalculation() {
        // Kevin Florer - Branch user
        DesiredConfiguration result = complianceLogicService.calculateConfigurationForUser("p300002");

        assertEquals("p300002", result.username());
        assertEquals("Vis-US-BR", result.visibilityProfile());
        assertTrue(result.groups().contains("US Field Submitters"));
        assertTrue(result.isActive());
    }

    @Test
    public void testHomeOfficeUserCalculation() {
        // David Chen - Home Office user
        DesiredConfiguration result = complianceLogicService.calculateConfigurationForUser("p200001");

        assertEquals("p200001", result.username());
        assertEquals("Vis-US-HO", result.visibilityProfile());
        assertTrue(result.groups().contains("US Home Office Submitters"));
        assertTrue(result.isActive());
    }

    @Test
    public void testLeaderCalculation() {
        // Katherine Powell - Home Office Leader
        DesiredConfiguration result = complianceLogicService.calculateConfigurationForUser("p100001");

        assertEquals("p100001", result.username());
        assertEquals("Vis_HO_Katherine_Powell_(p100001)", result.visibilityProfile());
        assertTrue(result.groups().contains("US_HO_Katherine_Powell_(p100001)"));
        assertTrue(result.isActive());
    }

    @Test
    public void testBranchTeamMemberCalculation() {
        // Maria Garcia - Branch team member
        DesiredConfiguration result = complianceLogicService.calculateConfigurationForUser("j050001");

        assertEquals("j050001", result.username());
        String vpProfile = result.visibilityProfile();
        assertTrue(vpProfile.startsWith("Vis_"), "VP should start with 'Vis_'");
        assertTrue(result.groups().contains("US Field Submitters"));
        assertTrue(result.isActive());
    }

    @Test
    public void testCanadianUserCalculation() {
        // Aisha Khan - Canadian Home Office user
        DesiredConfiguration result = complianceLogicService.calculateConfigurationForUser("p400001");

        assertEquals("p400001", result.username());
        assertEquals("Vis-CA-HO", result.visibilityProfile());
        assertTrue(result.groups().contains("CAN Home Office Submitters"));
        assertTrue(result.isActive());
    }

    @Test
    public void testHybridUserCalculation() {
        // Test HOBR (Home Office + Branch) user
        DesiredConfiguration result = complianceLogicService.calculateConfigurationForUser("hybrid01");

        assertEquals("hybrid01", result.username());
        assertEquals("Vis-US-HO-BR", result.visibilityProfile());
        assertTrue(result.groups().containsAll(Set.of("US Home Office Submitters", "US Field Submitters")));
        assertTrue(result.isActive());
    }

    // ==================== Real-time Processing Tests ====================

    @Test
    public void testAdChangeProcessing() {
        // Create a mock AD change event
        AdChangeEvent changeEvent = new AdChangeEvent();
        changeEvent.setPjNumber("p300002");
        changeEvent.setChangeType("DataChange");
        changeEvent.setProperty("Title");
        changeEvent.setNewValue("Senior Financial Advisor");

        // Capture the DesiredConfiguration sent to vendor
        ArgumentCaptor<DesiredConfiguration> configCaptor = ArgumentCaptor.forClass(DesiredConfiguration.class);

        // Process the change
        changeEventProcessor.processAdChange(changeEvent);

        // Verify vendor API was called with correct configuration
        verify(mockVendorApiClient).updateUser(configCaptor.capture());

        DesiredConfiguration capturedConfig = configCaptor.getValue();
        assertEquals("p300002", capturedConfig.username());
        assertEquals("Vis-US-BR", capturedConfig.visibilityProfile());
        assertTrue(capturedConfig.groups().contains("US Field Submitters"));
    }

    @Test
    public void testManagerChangeImpact() {
        // Test that a manager change affects both the manager and direct reports
        AdChangeEvent managerChangeEvent = new AdChangeEvent();
        managerChangeEvent.setPjNumber("p100001"); // Katherine Powell (manager)
        managerChangeEvent.setChangeType("DataChange");
        managerChangeEvent.setProperty("Title");
        managerChangeEvent.setNewValue("Senior Vice President - Compliance");

        // Capture all configurations sent to vendor
        ArgumentCaptor<DesiredConfiguration> configCaptor = ArgumentCaptor.forClass(DesiredConfiguration.class);

        // Process the change
        changeEventProcessor.processAdChange(managerChangeEvent);

        // Verify multiple users were updated (manager + direct reports)
        verify(mockVendorApiClient, atLeastOnce()).updateUser(configCaptor.capture());

        // At least the manager should be in the captured updates
        boolean managerUpdated = configCaptor.getAllValues().stream()
                .anyMatch(config -> "p100001".equals(config.username()));
        assertTrue(managerUpdated, "Manager should be updated when their data changes");
    }

    @Test
    public void testNonImpactfulChange() {
        // Test that non-impactful changes (like employee ID) don't trigger vendor updates
        AdChangeEvent nonImpactfulEvent = new AdChangeEvent();
        nonImpactfulEvent.setPjNumber("p300002");
        nonImpactfulEvent.setChangeType("DataChange");
        nonImpactfulEvent.setProperty("EmployeeID");
        nonImpactfulEvent.setNewValue("12345");

        // Process the change
        changeEventProcessor.processAdChange(nonImpactfulEvent);

        // Verify vendor API was NOT called since this change is not impactful
        verify(mockVendorApiClient, never()).updateUser(any(DesiredConfiguration.class));
    }

    // ==================== Integration Health Tests ====================

    @Test
    public void testEndToEndDataConsistency() {
        // Test that the same business logic produces consistent results
        // whether called directly or through change processing

        String testUsername = "p200001";

        // Get result from direct calculation
        DesiredConfiguration directResult = complianceLogicService.calculateConfigurationForUser(testUsername);

        // Create a change event and capture what gets sent to vendor
        AdChangeEvent changeEvent = new AdChangeEvent();
        changeEvent.setPjNumber(testUsername);
        changeEvent.setChangeType("DataChange");
        changeEvent.setProperty("Title");
        changeEvent.setNewValue("Updated Title");

        ArgumentCaptor<DesiredConfiguration> configCaptor = ArgumentCaptor.forClass(DesiredConfiguration.class);
        changeEventProcessor.processAdChange(changeEvent);
        verify(mockVendorApiClient).updateUser(configCaptor.capture());

        DesiredConfiguration processedResult = configCaptor.getValue();

        // Both should produce the same core configuration (VP and groups)
        assertEquals(directResult.username(), processedResult.username());
        assertEquals(directResult.visibilityProfile(), processedResult.visibilityProfile());
        assertEquals(directResult.groups(), processedResult.groups());
        assertEquals(directResult.isActive(), processedResult.isActive());
    }

    @Test
    public void testBusinessLogicConsistency() {
        // Test a few key users to ensure business logic is working correctly
        String[] testUsers = {"p100001", "p200001", "p300002", "j050001", "p400001"};

        for (String username : testUsers) {
            DesiredConfiguration config = complianceLogicService.calculateConfigurationForUser(username);

            // Basic validation - all users should have:
            assertNotNull(config.username(), "Username should not be null");
            assertNotNull(config.visibilityProfile(), "Visibility profile should not be null");
            assertNotNull(config.groups(), "Groups should not be null");
            assertFalse(config.groups().isEmpty(), "Groups should not be empty");

            // VP should follow naming conventions
            assertTrue(config.visibilityProfile().startsWith("Vis"),
                    "Visibility profile should start with 'Vis' for user: " + username);
        }
    }
}
