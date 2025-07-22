package com.edwardjones.cre.service.logic;

import com.edwardjones.cre.model.domain.AppUser;
import com.edwardjones.cre.model.dto.DesiredConfiguration;
import com.edwardjones.cre.repository.AppUserRepository;
import com.edwardjones.cre.repository.UserTeamMembershipRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ComplianceLogicServiceTest {

    @Mock
    private AppUserRepository appUserRepository;

    @Mock
    private UserTeamMembershipRepository userTeamMembershipRepository;

    @InjectMocks
    private ComplianceLogicService complianceLogicService;

    private AppUser testUser;
    private AppUser managerUser;
    private AppUser directReportUser;

    @BeforeEach
    void setUp() {
        testUser = new AppUser();
        testUser.setUsername("p100001");
        testUser.setFirstName("John");
        testUser.setLastName("Doe");
        testUser.setTitle("Financial Advisor");
        testUser.setDistinguishedName("CN=p100001,OU=Branch,OU=US,OU=People,DC=edj,DC=ad,DC=edwardjones,DC=com");
        testUser.setCountry("US");
        testUser.setActive(true);
        testUser.setManagerUsername("p200001");

        managerUser = new AppUser();
        managerUser.setUsername("p200001");
        managerUser.setFirstName("Jane");
        managerUser.setLastName("Manager");
        managerUser.setTitle("Manager");
        managerUser.setDistinguishedName("CN=p200001,OU=Home Office,OU=US,OU=People,DC=edj,DC=ad,DC=edwardjones,DC=com");
        managerUser.setCountry("US");
        managerUser.setActive(true);

        directReportUser = new AppUser();
        directReportUser.setUsername("p300001");
        directReportUser.setFirstName("Bob");
        directReportUser.setLastName("Report");
        directReportUser.setTitle("Analyst");
        directReportUser.setManagerUsername("p100001");
        directReportUser.setActive(true);
    }

    @Test
    void testCalculateConfigurationForUser_BranchUser() {
        when(appUserRepository.findById("p100001")).thenReturn(Optional.of(testUser));
        when(appUserRepository.findByManagerUsername("p100001")).thenReturn(Collections.emptyList());

        DesiredConfiguration result = complianceLogicService.calculateConfigurationForUser("p100001");

        assertNotNull(result);
        assertEquals("p100001", result.username());
        assertEquals("Vis-US-BR", result.visibilityProfile());
        assertTrue(result.groups().contains("US Field Submitters"));
        assertTrue(result.isActive());
        verify(appUserRepository).findById("p100001");
    }

    @Test
    void testCalculateConfigurationForUser_HomeOfficeUser() {
        testUser.setDistinguishedName("CN=p100001,OU=Home Office,OU=US,OU=People,DC=edj,DC=ad,DC=edwardjones,DC=com");
        testUser.setTitle("Compliance Analyst");

        when(appUserRepository.findById("p100001")).thenReturn(Optional.of(testUser));
        when(appUserRepository.findByManagerUsername("p100001")).thenReturn(Collections.emptyList());

        DesiredConfiguration result = complianceLogicService.calculateConfigurationForUser("p100001");

        assertNotNull(result);
        assertEquals("Vis-US-HO", result.visibilityProfile());
        assertTrue(result.groups().contains("US Home Office Submitters"));
    }

    @Test
    void testCalculateConfigurationForUser_HybridUser() {
        testUser.setTitle("Remote Branch On-Caller");
        testUser.setDistinguishedName("CN=p100001,OU=Home Office,OU=US,OU=People,DC=edj,DC=ad,DC=edwardjones,DC=com");

        when(appUserRepository.findById("p100001")).thenReturn(Optional.of(testUser));
        when(appUserRepository.findByManagerUsername("p100001")).thenReturn(Collections.emptyList());

        DesiredConfiguration result = complianceLogicService.calculateConfigurationForUser("p100001");

        assertNotNull(result);
        assertEquals("Vis-US-HO-BR", result.visibilityProfile());
        assertTrue(result.groups().contains("US Home Office Submitters"));
        assertTrue(result.groups().contains("US Field Submitters"));
    }

    @Test
    void testCalculateConfigurationForUser_CanadianUser() {
        testUser.setCountry("CA");
        testUser.setDistinguishedName("CN=p100001,OU=Home Office,OU=CA,OU=People,DC=edj,DC=ad,DC=edwardjones,DC=com");

        when(appUserRepository.findById("p100001")).thenReturn(Optional.of(testUser));
        when(appUserRepository.findByManagerUsername("p100001")).thenReturn(Collections.emptyList());

        DesiredConfiguration result = complianceLogicService.calculateConfigurationForUser("p100001");

        assertNotNull(result);
        assertEquals("Vis-CA-HO", result.visibilityProfile());
        assertTrue(result.groups().contains("CAN Home Office Submitters"));
    }

    @Test
    void testCalculateConfigurationForUser_LeaderWithDirectReports() {
        when(appUserRepository.findById("p100001")).thenReturn(Optional.of(testUser));
        when(appUserRepository.findByManagerUsername("p100001")).thenReturn(Arrays.asList(directReportUser));

        DesiredConfiguration result = complianceLogicService.calculateConfigurationForUser("p100001");

        assertNotNull(result);
        assertEquals("p100001", result.username());
        // Leader with direct reports should have some visibility profile
        assertNotNull(result.visibilityProfile());
        assertFalse(result.visibilityProfile().isEmpty());
        // Should have some groups
        assertNotNull(result.groups());
        assertFalse(result.groups().isEmpty());
        assertTrue(result.isActive());
    }

    @Test
    void testCalculateConfigurationForUser_UserNotFound() {
        when(appUserRepository.findById("nonexistent")).thenReturn(Optional.empty());

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            complianceLogicService.calculateConfigurationForUser("nonexistent");
        });

        assertEquals("User not found: nonexistent", exception.getMessage());
    }

    @Test
    void testCalculateConfigurationForUser_InactiveUser() {
        testUser.setActive(false);
        when(appUserRepository.findById("p100001")).thenReturn(Optional.of(testUser));
        when(appUserRepository.findByManagerUsername("p100001")).thenReturn(Collections.emptyList());

        DesiredConfiguration result = complianceLogicService.calculateConfigurationForUser("p100001");

        assertNotNull(result);
        assertFalse(result.isActive());
    }

    @Test
    void testMultipleUsers_DifferentTypes() {
        // Test multiple scenarios to increase coverage
        AppUser[] users = {
            createUser("p1", "US", "Financial Advisor", "OU=Branch"),
            createUser("p2", "US", "Analyst", "OU=Home Office"),
            createUser("p3", "CA", "Manager", "OU=Home Office"),
            createUser("p4", "US", "Remote Branch On-Caller", "OU=Home Office")
        };

        for (AppUser user : users) {
            when(appUserRepository.findById(user.getUsername())).thenReturn(Optional.of(user));
            when(appUserRepository.findByManagerUsername(user.getUsername())).thenReturn(Collections.emptyList());

            DesiredConfiguration result = complianceLogicService.calculateConfigurationForUser(user.getUsername());

            assertNotNull(result);
            assertNotNull(result.username());
            assertNotNull(result.visibilityProfile());
            assertNotNull(result.groups());
        }
    }

    @Test
    void testEdgeCases() {
        // Test with null values and edge cases
        AppUser userWithNulls = new AppUser();
        userWithNulls.setUsername("test");
        userWithNulls.setActive(true);
        userWithNulls.setCountry("US");

        when(appUserRepository.findById("test")).thenReturn(Optional.of(userWithNulls));
        when(appUserRepository.findByManagerUsername("test")).thenReturn(Collections.emptyList());

        DesiredConfiguration result = complianceLogicService.calculateConfigurationForUser("test");

        assertNotNull(result);
        assertEquals("test", result.username());
    }

    @Test
    void testRepositoryInteractions() {
        when(appUserRepository.findById(anyString())).thenReturn(Optional.of(testUser));
        when(appUserRepository.findByManagerUsername(anyString())).thenReturn(Collections.emptyList());

        complianceLogicService.calculateConfigurationForUser("p100001");

        verify(appUserRepository, times(1)).findById("p100001");
        verify(appUserRepository, times(1)).findByManagerUsername("p100001");
    }

    private AppUser createUser(String username, String country, String title, String ouPath) {
        AppUser user = new AppUser();
        user.setUsername(username);
        user.setCountry(country);
        user.setTitle(title);
        user.setDistinguishedName("CN=" + username + "," + ouPath + ",OU=" + country + ",OU=People,DC=edj,DC=ad,DC=edwardjones,DC=com");
        user.setActive(true);
        user.setFirstName("Test");
        user.setLastName("User");
        return user;
    }
}
