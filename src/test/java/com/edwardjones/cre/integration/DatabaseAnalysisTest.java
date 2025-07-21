package com.edwardjones.cre.integration;

import com.edwardjones.cre.business.ComplianceLogicService;
import com.edwardjones.cre.client.AdLdapClient;
import com.edwardjones.cre.client.CrbtApiClient;
import com.edwardjones.cre.model.domain.AppUser;
import com.edwardjones.cre.model.domain.CrbtTeam;
import com.edwardjones.cre.model.domain.UserTeamMembership;
import com.edwardjones.cre.model.dto.AdChangeEvent;
import com.edwardjones.cre.model.dto.CrtChangeEvent;
import com.edwardjones.cre.repository.AppUserRepository;
import com.edwardjones.cre.repository.CrbtTeamRepository;
import com.edwardjones.cre.repository.UserTeamMembershipRepository;
import com.edwardjones.cre.service.realtime.ChangeEventProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class DatabaseAnalysisTest {

    private static final Logger log = LoggerFactory.getLogger(DatabaseAnalysisTest.class);

    @Autowired
    private AdLdapClient adLdapClient;

    @Autowired
    private CrbtApiClient crbtApiClient;

    @Autowired
    private ComplianceLogicService complianceLogicService;

    @Autowired
    private ChangeEventProcessor changeEventProcessor;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private CrbtTeamRepository crbtTeamRepository;

    @Autowired
    private UserTeamMembershipRepository userTeamMembershipRepository;

    @Test
    @Order(1)
    public void testDataStructureValidation() {
        log.info("=== Testing Mock Data Structure Validation ===");

        // Test CRT Kafka CDC Event Structure
        testCrtKafkaCdcStructure();

        // Test AD Kafka CDC Event Structure
        testAdKafkaCdcStructure();

        // Test CRT REST API Structure
        testCrtRestApiStructure();

        // Test AD LDAP Query Structure
        testAdLdapStructure();

        log.info("✅ Data structure validation passed");
    }

    @Test
    @Order(2)
    public void testBootstrapDataLoading() {
        log.info("=== Testing Bootstrap Data Loading ===");

        // Test AD client mock data loading
        List<AppUser> users = adLdapClient.fetchAllUsers();
        assertNotNull(users, "Users list should not be null");
        assertFalse(users.isEmpty(), "Users list should not be empty");

        log.info("Loaded {} users from AD mock data", users.size());

        // Verify specific users exist with correct structure
        Optional<AppUser> testUser = users.stream()
                .filter(u -> "p198576".equals(u.getUsername()))
                .findFirst();
        assertTrue(testUser.isPresent(), "Test user p198576 should exist");
        assertEquals("0198576", testUser.get().getEmployeeId(), "Employee ID should match");

        // Test CRT client mock data loading
        List<CrbtTeam> teams = crbtApiClient.fetchAllTeams();
        assertNotNull(teams, "Teams list should not be null");
        assertFalse(teams.isEmpty(), "Teams list should not be empty");

        log.info("Loaded {} teams from CRT mock data", teams.size());

        // Verify team structure
        Optional<CrbtTeam> testTeam = teams.stream()
                .filter(t -> Integer.valueOf(312595).equals(t.getCrbtId()))
                .findFirst();
        assertTrue(testTeam.isPresent(), "Test team 312595 should exist");
        assertEquals("ACM", testTeam.get().getTeamType(), "Team type should be ACM");

        log.info("✅ Bootstrap data loading validation passed");
    }

    @Test
    @Order(3)
    public void testDatabaseStateAfterBootstrap() {
        log.info("=== Testing Database State After Bootstrap ===");

        // Verify users were saved to database
        List<AppUser> dbUsers = appUserRepository.findAll();
        assertFalse(dbUsers.isEmpty(), "Database should contain users after bootstrap");

        log.info("Database contains {} users", dbUsers.size());

        // Verify teams were saved to database
        List<CrbtTeam> dbTeams = crbtTeamRepository.findAll();
        assertFalse(dbTeams.isEmpty(), "Database should contain teams after bootstrap");

        log.info("Database contains {} teams", dbTeams.size());

        // Verify team memberships were created
        List<UserTeamMembership> memberships = userTeamMembershipRepository.findAll();
        assertFalse(memberships.isEmpty(), "Database should contain team memberships after bootstrap");

        log.info("Database contains {} team memberships", memberships.size());

        // Test critical user lookup by employee ID (critical for CRT event processing)
        Optional<AppUser> userByEmpId = appUserRepository.findByEmployeeId("0198576");
        assertTrue(userByEmpId.isPresent(), "User should be findable by employee ID");
        assertEquals("p198576", userByEmpId.get().getUsername(), "Username should match for employee ID lookup");

        log.info("✅ Database state validation passed");
    }

    @Test
    @Order(4)
    public void testDataConsistencyAndStructure() {
        log.info("=== Testing Data Consistency and Structure ===");

        // Test that all team members in CRT data have corresponding users in AD data
        List<CrbtApiClient.CrbtApiTeamResponse> teamsWithMembers = crbtApiClient.fetchAllTeamsWithMembers();

        int missingUserCount = 0;
        int totalMemberCount = 0;

        for (CrbtApiClient.CrbtApiTeamResponse team : teamsWithMembers) {
            for (CrbtApiClient.CrbtApiTeamResponse.CrbtApiMember member : team.memberList) {
                totalMemberCount++;
                Optional<AppUser> user = appUserRepository.findByEmployeeId(member.mbrEmplId);
                if (user.isEmpty()) {
                    log.warn("Missing user for employee ID: {} (P/J: {})", member.mbrEmplId, member.mbrJorP);
                    missingUserCount++;
                }
            }
        }

        log.info("Data consistency check: {}/{} team members have corresponding users",
                totalMemberCount - missingUserCount, totalMemberCount);

        assertEquals(0, missingUserCount, "All team members should have corresponding users in AD data");

        log.info("✅ Data consistency and structure validation passed");
    }

    private void testCrtKafkaCdcStructure() {
        log.info("Testing CRT Kafka CDC Event Structure");

        // Create event matching real CRT Kafka CDC structure
        CrtChangeEvent crtEvent = new CrtChangeEvent();
        crtEvent.setCrbtId(312595);
        crtEvent.setIrNumber(903422);
        crtEvent.setBrNumber(29870);
        crtEvent.setCrbtName("JOHN FRANK/ KEVIN FLORER");
        crtEvent.setEffectiveBeginDate("2025-05-12"); // Simple date format
        crtEvent.setEffectiveEndDate(null);
        crtEvent.setAdvisoryLetterType("A");
        crtEvent.setTeamType("ACM");
        crtEvent.setDisplayNameType("L");
        crtEvent.setAssistantSubTeamId(null);

        CrtChangeEvent.CrtMemberChange memberChange = new CrtChangeEvent.CrtMemberChange();
        memberChange.setEmployeeId("0104554");
        memberChange.setRole("BOA");
        memberChange.setRolePriority(null);
        memberChange.setEffectiveBeginDate("2025-07-19");
        memberChange.setEffectiveEndDate(null);
        memberChange.setEjcIndicator("N");
        crtEvent.setMembers(memberChange);

        // Validate structure
        assertNotNull(crtEvent.getCrbtId(), "crbtId should be set");
        assertNotNull(crtEvent.getMembers(), "members should be set");
        assertEquals("0104554", crtEvent.getMemberEmployeeId(), "Member employee ID should match");
        assertTrue(crtEvent.isMembershipChange(), "Should detect membership change");

        log.info("✅ CRT Kafka CDC structure validation passed");
    }

    private void testAdKafkaCdcStructure() {
        log.info("Testing AD Kafka CDC Event Structure");

        // Test different AD change types
        AdChangeEvent newUserEvent = new AdChangeEvent();
        newUserEvent.setAfterDate("07-01-2025");
        newUserEvent.setBeforeDate("06-30-2025");
        newUserEvent.setProcessedDate(null);
        newUserEvent.setPjNumber("j10441");
        newUserEvent.setChangeType("NewUser");
        newUserEvent.setProperty("Name");
        newUserEvent.setBeforeValue(null);
        newUserEvent.setNewValue("j10441");

        assertTrue(newUserEvent.isNewUser(), "Should detect NewUser event");
        assertTrue(newUserEvent.isLifecycleEvent(), "Should detect lifecycle event");
        assertEquals("j10441", newUserEvent.getUserIdentifier(), "User identifier should match");

        AdChangeEvent dataChangeEvent = new AdChangeEvent();
        dataChangeEvent.setAfterDate("07-01-2025");
        dataChangeEvent.setPjNumber("p007435");
        dataChangeEvent.setChangeType("DataChange");
        dataChangeEvent.setProperty("ej-IRNumber");
        dataChangeEvent.setBeforeValue("123456");
        dataChangeEvent.setNewValue("420659");

        assertTrue(dataChangeEvent.isDataChange(), "Should detect DataChange event");
        assertTrue(dataChangeEvent.isImpactfulChange(), "ej-IRNumber change should be impactful");

        log.info("✅ AD Kafka CDC structure validation passed");
    }

    private void testCrtRestApiStructure() {
        log.info("Testing CRT REST API Structure");

        // Load actual CRT REST API response
        List<CrbtApiClient.CrbtApiTeamResponse> teams = crbtApiClient.fetchAllTeamsWithMembers();
        assertFalse(teams.isEmpty(), "Should load teams from REST API mock data");

        CrbtApiClient.CrbtApiTeamResponse team = teams.get(0);
        assertEquals("JOHN FRANK/ KEVIN FLORER", team.teamName, "Team name should match");
        assertEquals(Integer.valueOf(312595), team.crbtID, "Team ID should match");
        assertEquals("ACM", team.teamTyCd, "Team type should be ACM");
        assertNotNull(team.memberList, "Member list should not be null");
        assertFalse(team.memberList.isEmpty(), "Member list should not be empty");

        // Test member structure
        CrbtApiClient.CrbtApiTeamResponse.CrbtApiMember member = team.memberList.get(0);
        assertNotNull(member.mbrEmplId, "Member employee ID should not be null");
        assertNotNull(member.mbrRoleCd, "Member role should not be null");
        assertNotNull(member.mbrJorP, "Member P/J number should not be null");

        log.info("✅ CRT REST API structure validation passed");
    }

    private void testAdLdapStructure() {
        log.info("Testing AD LDAP Query Structure");

        // Load actual AD LDAP response
        List<AppUser> users = adLdapClient.fetchAllUsers();
        assertFalse(users.isEmpty(), "Should load users from LDAP mock data");

        AppUser user = users.get(0);
        assertNotNull(user.getUsername(), "Username should not be null");
        assertNotNull(user.getEmployeeId(), "Employee ID should not be null");
        assertNotNull(user.getFirstName(), "First name should not be null");
        assertNotNull(user.getLastName(), "Last name should not be null");
        assertNotNull(user.getCountry(), "Country should not be null");
        assertNotNull(user.getDistinguishedName(), "Distinguished name should not be null");

        // Verify structure matches real AD LDAP response format
        assertTrue(user.getDistinguishedName().startsWith("CN="), "DN should start with CN=");
        assertTrue(user.getEmployeeId().matches("\\d{7}"), "Employee ID should be 7 digits");

        log.info("✅ AD LDAP structure validation passed");
    }
}
