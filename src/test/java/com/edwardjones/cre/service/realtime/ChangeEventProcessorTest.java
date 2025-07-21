package com.edwardjones.cre.service.realtime;

import com.edwardjones.cre.business.ComplianceLogicService;
import com.edwardjones.cre.client.VendorApiClient;
import com.edwardjones.cre.model.domain.AppUser;
import com.edwardjones.cre.model.domain.CrbtTeam;
import com.edwardjones.cre.model.domain.UserTeamMembership;
import com.edwardjones.cre.model.domain.UserTeamMembershipId;
import com.edwardjones.cre.model.dto.AdChangeEvent;
import com.edwardjones.cre.model.dto.CrtChangeEvent;
import com.edwardjones.cre.repository.AppUserRepository;
import com.edwardjones.cre.repository.CrbtTeamRepository;
import com.edwardjones.cre.repository.UserTeamMembershipRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChangeEventProcessor Tests")
class ChangeEventProcessorTest {

    @Mock
    private AppUserRepository appUserRepository;

    @Mock
    private CrbtTeamRepository crbtTeamRepository;

    @Mock
    private UserTeamMembershipRepository userTeamMembershipRepository;

    @Mock
    private ComplianceLogicService complianceLogicService;

    @Mock
    private VendorApiClient vendorApiClient;

    @InjectMocks
    private ChangeEventProcessor changeEventProcessor;

    private AppUser testUser;
    private AppUser managerUser;
    private AppUser directReportUser;
    private CrbtTeam testTeam;
    private UserTeamMembership testMembership;

    @BeforeEach
    void setUp() {
        // Set up test users
        testUser = createTestUser("p007435", "420659", "Senior Advisor", "p123456",
                                 "CN=p007435,OU=STL,DC=edwardjones,DC=com", "MO", true);

        managerUser = createTestUser("p123456", "123456", "Manager", null,
                                   "CN=p123456,OU=STL,DC=edwardjones,DC=com", "MO", true);

        directReportUser = createTestUser("p456789", "456789", "Analyst", "p007435",
                                        "CN=p456789,OU=STL,DC=edwardjones,DC=com", "MO", true);

        // Set up test team
        testTeam = createTestTeam(312595, "JOHN FRANK/ KEVIN FLORER", "VTM", true);

        // Set up test membership
        testMembership = createTestMembership(testUser, testTeam, "BOA");
    }

    @Nested
    @DisplayName("AD Change Processing Tests")
    class AdChangeProcessingTests {

        @Test
        @DisplayName("Should process new user event correctly")
        void shouldProcessNewUserEvent() {
            // Given
            AdChangeEvent newUserEvent = createAdChangeEvent("j10441", "NewUser", "Name", null, "j10441");
            when(appUserRepository.findById("j10441")).thenReturn(Optional.empty());

            // When
            changeEventProcessor.processAdChange(newUserEvent);

            // Then
            verify(appUserRepository).findById("j10441");
            // For new users, the current implementation just logs - no database operations expected
            verifyNoMoreInteractions(appUserRepository);
            verifyNoInteractions(vendorApiClient);
        }

        @Test
        @DisplayName("Should handle duplicate new user event gracefully")
        void shouldHandleDuplicateNewUserEvent() {
            // Given
            AdChangeEvent newUserEvent = createAdChangeEvent("p007435", "NewUser", "Name", null, "p007435");
            when(appUserRepository.findById("p007435")).thenReturn(Optional.of(testUser));

            // When
            changeEventProcessor.processAdChange(newUserEvent);

            // Then
            verify(appUserRepository).findById("p007435");
            verifyNoMoreInteractions(appUserRepository);
            verifyNoInteractions(vendorApiClient);
        }

        @Test
        @DisplayName("Should process user termination correctly")
        void shouldProcessUserTermination() {
            // Given
            AdChangeEvent terminationEvent = createAdChangeEvent("p007435", "TerminatedUser", "Name", "p007435", null);
            when(appUserRepository.findById("p007435")).thenReturn(Optional.of(testUser));
            when(complianceLogicService.calculateConfigurationForUser("p007435")).thenReturn(testUser);

            // When
            changeEventProcessor.processAdChange(terminationEvent);

            // Then
            verify(appUserRepository).findById("p007435");
            verify(appUserRepository).save(testUser);
            verify(complianceLogicService).calculateConfigurationForUser("p007435");
            verify(vendorApiClient).updateUser(testUser);
            assertThat(testUser.isActive()).isFalse();
        }

        @Test
        @DisplayName("Should handle termination event for non-existent user")
        void shouldHandleTerminationForNonExistentUser() {
            // Given
            AdChangeEvent terminationEvent = createAdChangeEvent("p999999", "TerminatedUser", "Name", "p999999", null);
            when(appUserRepository.findById("p999999")).thenReturn(Optional.empty());

            // When
            changeEventProcessor.processAdChange(terminationEvent);

            // Then
            verify(appUserRepository).findById("p999999");
            verifyNoMoreInteractions(appUserRepository);
            verifyNoInteractions(vendorApiClient);
        }

        @Test
        @DisplayName("Should process employee ID change correctly")
        void shouldProcessEmployeeIdChange() {
            // Given
            AdChangeEvent employeeIdChange = createAdChangeEvent("p007435", "DataChange", "ej-IRNumber", "123456", "420659");
            when(appUserRepository.findById("p007435")).thenReturn(Optional.of(testUser));
            when(complianceLogicService.calculateConfigurationForUser("p007435"))
                    .thenReturn(testUser); // old config

            // When
            changeEventProcessor.processAdChange(employeeIdChange);

            // Then
            verify(appUserRepository).findById("p007435");
            // Employee ID changes are not impactful, so no save or vendor API call expected
            verify(appUserRepository, never()).save(any());
            verify(complianceLogicService).calculateConfigurationForUser("p007435");
            verifyNoInteractions(vendorApiClient);
            // The user object should still be updated in memory even if not saved
            assertThat(testUser.getEmployeeId()).isEqualTo("420659");
        }

        @Test
        @DisplayName("Should process state change and trigger vendor update")
        void shouldProcessStateChangeAndTriggerVendorUpdate() {
            // Given
            AdChangeEvent stateChange = createAdChangeEvent("p231756", "DataChange", "State", "YY", "TX");
            AppUser userWithStateChange = createTestUser("p231756", "365079", "Advisor", "p123456",
                    "CN=p231756,OU=STL,DC=edwardjones,DC=com", "YY", true);
            AppUser oldConfig = createTestUser("p231756", "365079", "Advisor", "p123456",
                    "CN=p231756,OU=STL,DC=edwardjones,DC=com", "YY", true);
            AppUser newConfig = createTestUser("p231756", "365079", "Advisor", "p123456",
                    "CN=p231756,OU=STL,DC=edwardjones,DC=com", "TX", true);

            when(appUserRepository.findById("p231756")).thenReturn(Optional.of(userWithStateChange));
            when(complianceLogicService.calculateConfigurationForUser("p231756"))
                    .thenReturn(oldConfig) // old config
                    .thenReturn(newConfig); // new config

            // When
            changeEventProcessor.processAdChange(stateChange);

            // Then
            verify(appUserRepository).findById("p231756");
            verify(appUserRepository).save(userWithStateChange);
            verify(complianceLogicService, times(2)).calculateConfigurationForUser("p231756");
            verify(vendorApiClient).updateUser(newConfig);
            assertThat(userWithStateChange.getCountry()).isEqualTo("TX");
        }

        @Test
        @DisplayName("Should process title change for manager and update direct reports")
        void shouldProcessTitleChangeForManagerAndUpdateDirectReports() {
            // Given
            AdChangeEvent titleChange = createAdChangeEvent("p007435", "DataChange", "Title", "Senior Advisor", "Lead Advisor");
            when(appUserRepository.findById("p007435")).thenReturn(Optional.of(testUser));
            when(appUserRepository.findByManagerUsername("p007435")).thenReturn(Arrays.asList(directReportUser));
            when(complianceLogicService.calculateConfigurationForUser("p007435"))
                    .thenReturn(testUser) // old config
                    .thenReturn(testUser); // new config (updated title)
            when(complianceLogicService.calculateConfigurationForUser("p456789")).thenReturn(directReportUser);

            // When
            changeEventProcessor.processAdChange(titleChange);

            // Then
            verify(appUserRepository).findById("p007435");
            verify(appUserRepository).save(testUser);
            verify(appUserRepository).findByManagerUsername("p007435");
            verify(complianceLogicService, times(2)).calculateConfigurationForUser("p007435");
            verify(complianceLogicService).calculateConfigurationForUser("p456789");
            verify(vendorApiClient).updateUser(directReportUser); // Direct report updated
            assertThat(testUser.getTitle()).isEqualTo("Lead Advisor");
        }

        @Test
        @DisplayName("Should handle manager change correctly")
        void shouldHandleManagerChange() {
            // Given
            AdChangeEvent managerChange = createAdChangeEvent("p007435", "DataChange", "Manager",
                    "CN=p123456,OU=STL,DC=edwardjones,DC=com", "CN=p654321,OU=STL,DC=edwardjones,DC=com");
            when(appUserRepository.findById("p007435")).thenReturn(Optional.of(testUser));
            when(complianceLogicService.calculateConfigurationForUser("p007435"))
                    .thenReturn(testUser); // old config

            // When
            changeEventProcessor.processAdChange(managerChange);

            // Then
            verify(appUserRepository).findById("p007435");
            // Manager changes are not impactful, so no save expected
            verify(appUserRepository, never()).save(any());
            verify(complianceLogicService).calculateConfigurationForUser("p007435");
            // Manager changes are not impactful to direct reports
            verify(appUserRepository, never()).findByManagerUsername(anyString());
            verifyNoInteractions(vendorApiClient);
            assertThat(testUser.getManagerUsername()).isEqualTo("p654321");
        }

        @Test
        @DisplayName("Should handle unknown change type gracefully")
        void shouldHandleUnknownChangeType() {
            // Given
            AdChangeEvent unknownEvent = createAdChangeEvent("p007435", "UnknownType", "SomeProperty", "oldValue", "newValue");

            // When
            changeEventProcessor.processAdChange(unknownEvent);

            // Then
            verifyNoInteractions(appUserRepository, vendorApiClient, complianceLogicService);
        }

        @Test
        @DisplayName("Should handle unhandled property change")
        void shouldHandleUnhandledPropertyChange() {
            // Given
            AdChangeEvent unhandledProperty = createAdChangeEvent("p007435", "DataChange", "UnknownProperty", "oldValue", "newValue");
            when(appUserRepository.findById("p007435")).thenReturn(Optional.of(testUser));
            when(complianceLogicService.calculateConfigurationForUser("p007435"))
                    .thenReturn(testUser); // old config only - unhandled properties are non-impactful

            // When
            changeEventProcessor.processAdChange(unhandledProperty);

            // Then
            verify(appUserRepository).findById("p007435");
            verify(appUserRepository, never()).save(any());
            // Unhandled properties are correctly identified as non-impactful, so only one calculation
            verify(complianceLogicService, times(1)).calculateConfigurationForUser("p007435");
            verifyNoInteractions(vendorApiClient);
        }
    }

    @Nested
    @DisplayName("CRT Change Processing Tests")
    class CrtChangeProcessingTests {

        @Test
        @DisplayName("Should process new member joining team")
        void shouldProcessNewMemberJoiningTeam() {
            // Given - VTM member joins scenario from test data
            CrtChangeEvent memberJoinsEvent = createCrtMemberJoinsEvent();
            AppUser newMember = createTestUser("p246517", "0246517", "Advisor", "p123456",
                    "CN=p246517,OU=STL,DC=edwardjones,DC=com", "MO", true);

            when(crbtTeamRepository.findById(312595)).thenReturn(Optional.of(testTeam));
            when(appUserRepository.findByEmployeeId("0246517")).thenReturn(Optional.of(newMember));
            when(userTeamMembershipRepository.findById(any(UserTeamMembershipId.class))).thenReturn(Optional.empty());
            when(complianceLogicService.calculateConfigurationForUser("p246517")).thenReturn(newMember);

            // When
            changeEventProcessor.processCrtChange(memberJoinsEvent);

            // Then
            verify(crbtTeamRepository).findById(312595);
            verify(appUserRepository).findByEmployeeId("0246517");
            verify(userTeamMembershipRepository).findById(any(UserTeamMembershipId.class));
            verify(userTeamMembershipRepository).save(any(UserTeamMembership.class));
            verify(complianceLogicService).calculateConfigurationForUser("p246517");
            verify(vendorApiClient).updateUser(newMember);
        }

        @Test
        @DisplayName("Should process role change for existing member")
        void shouldProcessRoleChangeForExistingMember() {
            // Given - VTM role change scenario from test data
            CrtChangeEvent roleChangeEvent = createCrtRoleChangeEvent();
            AppUser existingMember = createTestUser("p198576", "0198576", "Financial Advisor", "p123456",
                    "CN=p198576,OU=STL,DC=edwardjones,DC=com", "MO", true);
            UserTeamMembership existingMembership = createTestMembership(existingMember, testTeam, "FA");

            when(crbtTeamRepository.findById(312595)).thenReturn(Optional.of(testTeam));
            when(appUserRepository.findByEmployeeId("0198576")).thenReturn(Optional.of(existingMember));
            when(userTeamMembershipRepository.findById(any(UserTeamMembershipId.class))).thenReturn(Optional.of(existingMembership));
            when(complianceLogicService.calculateConfigurationForUser("p198576")).thenReturn(existingMember);
            when(appUserRepository.findByManagerUsername("p198576")).thenReturn(Arrays.asList(directReportUser));
            when(complianceLogicService.calculateConfigurationForUser("p456789")).thenReturn(directReportUser);

            // When
            changeEventProcessor.processCrtChange(roleChangeEvent);

            // Then
            verify(userTeamMembershipRepository).save(existingMembership);
            verify(complianceLogicService).calculateConfigurationForUser("p198576");
            verify(vendorApiClient).updateUser(existingMember);
            // Since this is a managerial change (LEAD role), direct reports should be updated
            verify(appUserRepository).findByManagerUsername("p198576");
            verify(complianceLogicService).calculateConfigurationForUser("p456789");
            verify(vendorApiClient).updateUser(directReportUser);
            assertThat(existingMembership.getMemberRole()).isEqualTo("LEAD");
        }

        @Test
        @DisplayName("Should process member leaving team")
        void shouldProcessMemberLeavingTeam() {
            // Given - SFA member leaves scenario from test data
            CrtChangeEvent memberLeavesEvent = createCrtMemberLeavesEvent();
            AppUser leavingMember = createTestUser("p160002", "0160002", "Branch Operations Associate", "p123456",
                    "CN=p160002,OU=STL,DC=edwardjones,DC=com", "MO", true);
            UserTeamMembership membershipToRemove = createTestMembership(leavingMember, testTeam, "BOA");

            when(appUserRepository.findByEmployeeId("0160002")).thenReturn(Optional.of(leavingMember));
            when(userTeamMembershipRepository.findById(any(UserTeamMembershipId.class))).thenReturn(Optional.of(membershipToRemove));
            when(complianceLogicService.calculateConfigurationForUser("p160002")).thenReturn(leavingMember);

            // When
            changeEventProcessor.processCrtChange(memberLeavesEvent);

            // Then
            verify(appUserRepository).findByEmployeeId("0160002");
            verify(userTeamMembershipRepository).findById(any(UserTeamMembershipId.class));
            verify(userTeamMembershipRepository).delete(membershipToRemove);
            verify(complianceLogicService).calculateConfigurationForUser("p160002");
            verify(vendorApiClient).updateUser(leavingMember);
        }

        @Test
        @DisplayName("Should process team deactivation")
        void shouldProcessTeamDeactivation() {
            // Given
            CrtChangeEvent teamDeactivationEvent = createCrtTeamDeactivationEvent();
            List<UserTeamMembership> teamMemberships = Arrays.asList(
                    createTestMembership(testUser, testTeam, "BOA"),
                    createTestMembership(directReportUser, testTeam, "FA")
            );

            when(crbtTeamRepository.findById(312595)).thenReturn(Optional.of(testTeam));
            when(userTeamMembershipRepository.findByTeamCrbtId(312595)).thenReturn(teamMemberships);
            when(complianceLogicService.calculateConfigurationForUser("p007435")).thenReturn(testUser);
            when(complianceLogicService.calculateConfigurationForUser("p456789")).thenReturn(directReportUser);

            // When
            changeEventProcessor.processCrtChange(teamDeactivationEvent);

            // Then
            verify(crbtTeamRepository).findById(312595);
            verify(crbtTeamRepository).save(testTeam);
            verify(userTeamMembershipRepository).findByTeamCrbtId(312595);
            verify(userTeamMembershipRepository).deleteAll(teamMemberships);
            verify(complianceLogicService).calculateConfigurationForUser("p007435");
            verify(complianceLogicService).calculateConfigurationForUser("p456789");
            verify(vendorApiClient).updateUser(testUser);
            verify(vendorApiClient).updateUser(directReportUser);
            assertThat(testTeam.isActive()).isFalse();
        }

        @Test
        @DisplayName("Should handle CRT event for non-existent team")
        void shouldHandleCrtEventForNonExistentTeam() {
            // Given
            CrtChangeEvent eventForNonExistentTeam = createCrtMemberJoinsEvent();
            when(crbtTeamRepository.findById(312595)).thenReturn(Optional.empty());

            // When
            changeEventProcessor.processCrtChange(eventForNonExistentTeam);

            // Then
            verify(crbtTeamRepository).findById(312595);
            verifyNoMoreInteractions(crbtTeamRepository);
            verifyNoInteractions(appUserRepository, userTeamMembershipRepository, vendorApiClient);
        }

        @Test
        @DisplayName("Should handle CRT event for non-existent user")
        void shouldHandleCrtEventForNonExistentUser() {
            // Given
            CrtChangeEvent eventForNonExistentUser = createCrtMemberJoinsEvent();
            when(crbtTeamRepository.findById(312595)).thenReturn(Optional.of(testTeam));
            when(appUserRepository.findByEmployeeId("0246517")).thenReturn(Optional.empty());

            // When
            changeEventProcessor.processCrtChange(eventForNonExistentUser);

            // Then
            verify(crbtTeamRepository).findById(312595);
            verify(appUserRepository).findByEmployeeId("0246517");
            verifyNoMoreInteractions(appUserRepository);
            verifyNoInteractions(userTeamMembershipRepository, vendorApiClient);
        }

        @Test
        @DisplayName("Should handle member leaving event when membership doesn't exist")
        void shouldHandleMemberLeavingEventWhenMembershipDoesntExist() {
            // Given
            CrtChangeEvent memberLeavesEvent = createCrtMemberLeavesEvent();
            AppUser user = createTestUser("p160002", "0160002", "BOA", "p123456",
                    "CN=p160002,OU=STL,DC=edwardjones,DC=com", "MO", true);

            when(appUserRepository.findByEmployeeId("0160002")).thenReturn(Optional.of(user));
            when(userTeamMembershipRepository.findById(any(UserTeamMembershipId.class))).thenReturn(Optional.empty());

            // When
            changeEventProcessor.processCrtChange(memberLeavesEvent);

            // Then
            verify(appUserRepository).findByEmployeeId("0160002");
            verify(userTeamMembershipRepository).findById(any(UserTeamMembershipId.class));
            verify(userTeamMembershipRepository, never()).delete(any());
            verifyNoInteractions(vendorApiClient);
        }
    }

    // Helper methods to create test objects and events
    private AppUser createTestUser(String username, String employeeId, String title, String managerUsername,
                                  String distinguishedName, String country, boolean active) {
        AppUser user = new AppUser();
        user.setUsername(username);
        user.setEmployeeId(employeeId);
        user.setTitle(title);
        user.setManagerUsername(managerUsername);
        user.setDistinguishedName(distinguishedName);
        user.setCountry(country);
        user.setActive(active);
        return user;
    }

    private CrbtTeam createTestTeam(Integer crbtId, String teamName, String teamType, boolean active) {
        CrbtTeam team = new CrbtTeam();
        team.setCrbtId(crbtId);
        team.setTeamName(teamName);
        team.setTeamType(teamType);
        team.setActive(active);
        team.setEffectiveStartDate(LocalDate.now().minusDays(30));
        return team;
    }

    private UserTeamMembership createTestMembership(AppUser user, CrbtTeam team, String role) {
        UserTeamMembership membership = new UserTeamMembership();
        membership.setId(new UserTeamMembershipId(user.getUsername(), team.getCrbtId()));
        membership.setUser(user);
        membership.setTeam(team);
        membership.setMemberRole(role);
        membership.setEffectiveStartDate(LocalDate.now().minusDays(10));
        return membership;
    }

    private AdChangeEvent createAdChangeEvent(String pjNumber, String changeType, String property,
                                            String beforeValue, String newValue) {
        AdChangeEvent event = new AdChangeEvent();
        event.setPjNumber(pjNumber);
        event.setChangeType(changeType);
        event.setProperty(property);
        event.setBeforeValue(beforeValue);
        event.setNewValue(newValue);
        event.setAfterDate("07-20-2025");
        event.setBeforeDate("07-19-2025");
        return event;
    }

    private CrtChangeEvent createCrtMemberJoinsEvent() {
        CrtChangeEvent event = new CrtChangeEvent();
        event.setCrbtId(312595);
        event.setIrNumber(903422);
        event.setBrNumber(29870);
        event.setCrbtName("JOHN FRANK/ KEVIN FLORER");
        event.setEffectiveBeginDate("2025-05-12");
        event.setTeamType("VTM");

        CrtChangeEvent.CrtMemberChange member = new CrtChangeEvent.CrtMemberChange();
        member.setEmployeeId("0246517");
        member.setRole("BOA");
        member.setEffectiveBeginDate("2025-07-19");
        event.setMembers(member);

        return event;
    }

    private CrtChangeEvent createCrtRoleChangeEvent() {
        CrtChangeEvent event = new CrtChangeEvent();
        event.setCrbtId(312595);
        event.setIrNumber(903422);
        event.setBrNumber(29870);
        event.setCrbtName("JOHN FRANK/ KEVIN FLORER");
        event.setEffectiveBeginDate("2025-05-12");
        event.setTeamType("VTM");

        CrtChangeEvent.CrtMemberChange member = new CrtChangeEvent.CrtMemberChange();
        member.setEmployeeId("0198576");
        member.setRole("LEAD");
        member.setEffectiveBeginDate("2025-07-20");
        event.setMembers(member);

        return event;
    }

    private CrtChangeEvent createCrtMemberLeavesEvent() {
        CrtChangeEvent event = new CrtChangeEvent();
        event.setCrbtId(450112);
        event.setIrNumber(765432);
        event.setBrNumber(15432);
        event.setCrbtName("SARAH WILLIAMS SFA");
        event.setEffectiveBeginDate("2025-06-01");
        event.setTeamType("SFA");

        CrtChangeEvent.CrtMemberChange member = new CrtChangeEvent.CrtMemberChange();
        member.setEmployeeId("0160002");
        member.setRole("BOA");
        member.setEffectiveBeginDate("2025-06-01");
        member.setEffectiveEndDate("2025-07-20");
        event.setMembers(member);

        return event;
    }

    private CrtChangeEvent createCrtTeamDeactivationEvent() {
        CrtChangeEvent event = new CrtChangeEvent();
        event.setCrbtId(312595);
        event.setIrNumber(903422);
        event.setBrNumber(29870);
        event.setCrbtName("JOHN FRANK/ KEVIN FLORER");
        event.setEffectiveBeginDate("2025-05-12");
        event.setEffectiveEndDate("2025-07-20"); // Team deactivation
        event.setTeamType("VTM");

        return event;
    }
}
