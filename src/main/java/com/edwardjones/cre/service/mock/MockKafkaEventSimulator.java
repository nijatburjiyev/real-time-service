package com.edwardjones.cre.service.mock;

import com.edwardjones.cre.model.dto.AdChangeEvent;
import com.edwardjones.cre.model.dto.CrtChangeEvent;
import com.edwardjones.cre.service.realtime.ChangeEventProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * Updated Mock Kafka event simulator that uses realistic AD and CRBT data
 * to test the complete end-to-end flow with actual data structures.
 */
@Slf4j
@Service
@Profile("!test")
@RequiredArgsConstructor
public class MockKafkaEventSimulator {

    private final ChangeEventProcessor changeEventProcessor;
    private final Environment environment;

    /**
     * Automatically triggers realistic mock events after the application starts up
     * using the actual AD user data and CRBT team structures.
     * Disabled during tests to prevent interference.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void simulateKafkaEvents() {
        // Skip simulation when running in test profile
        if (isTestProfile()) {
            log.info("Mock Kafka event simulation skipped - running in test profile");
            return;
        }

        log.info("=== Starting Realistic Mock Kafka Event Simulation ===");

        // Wait a moment for bootstrap to complete
        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Simulate realistic AD change events
        simulateRealisticAdEvents();

        // Wait between event types
        try {
            Thread.sleep(1500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Simulate realistic CRT change events
        simulateRealisticCrtEvents();

        log.info("=== Realistic Mock Kafka Event Simulation Complete ===");
    }

    private boolean isTestProfile() {
        String[] activeProfiles = environment.getActiveProfiles();
        for (String profile : activeProfiles) {
            if ("test".equals(profile)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Simulates various AD change events using actual user data from your test JSON.
     */
    public void simulateRealisticAdEvents() {
        log.info("--- Simulating Realistic AD Change Events ---");

        // Test 1: Manager change for David Chen (p200001) - from Katherine to Maria
        AdChangeEvent managerChange = new AdChangeEvent();
        managerChange.setPjNumber("p200001"); // David Chen
        managerChange.setChangeType("UPDATE");
        managerChange.setProperty("Manager");
        managerChange.setBeforeValue("CN=p100001,OU=Managers,OU=Home Office,OU=US,OU=People,DC=edj,DC=ad,DC=edwardjones,DC=com");
        managerChange.setNewValue("CN=j050001,OU=Leaders,OU=Branch,OU=US,OU=People,DC=edj,DC=ad,DC=edwardjones,DC=com");

        log.info("🔄 Simulating manager change: {} -> new manager j050001", managerChange.getPjNumber());
        changeEventProcessor.processAdChange(managerChange);

        // Test 2: Title change for Katherine Powell (leader with direct reports)
        AdChangeEvent titleChange = new AdChangeEvent();
        titleChange.setPjNumber("p100001"); // Katherine Powell (has direct reports)
        titleChange.setChangeType("UPDATE");
        titleChange.setProperty("Title");
        titleChange.setBeforeValue("Vice President - Compliance");
        titleChange.setNewValue("Senior Vice President - Compliance");

        log.info("🔄 Simulating title change for leader: {} (impacts direct reports)", titleChange.getPjNumber());
        changeEventProcessor.processAdChange(titleChange);

        // Test 3: Distinguished Name change - John Frank moves from FA to BOA OU
        AdChangeEvent dnChange = new AdChangeEvent();
        dnChange.setPjNumber("p300001"); // John Frank
        dnChange.setChangeType("UPDATE");
        dnChange.setProperty("DistinguishedName");
        dnChange.setBeforeValue("CN=p300001,OU=FA,OU=Branch,OU=US,OU=People,DC=edj,DC=ad,DC=edwardjones,DC=com");
        dnChange.setNewValue("CN=p300001,OU=BOA,OU=Branch,OU=US,OU=People,DC=edj,DC=ad,DC=edwardjones,DC=com");

        log.info("🔄 Simulating DN change for user: {} (OU change)", dnChange.getPjNumber());
        changeEventProcessor.processAdChange(dnChange);

        // Test 4: User activation/deactivation
        AdChangeEvent enabledChange = new AdChangeEvent();
        enabledChange.setPjNumber("p400001"); // Aisha Khan
        enabledChange.setChangeType("UPDATE");
        enabledChange.setProperty("Enabled");
        enabledChange.setBeforeValue("true");
        enabledChange.setNewValue("false");

        log.info("🔄 Simulating user deactivation: {}", enabledChange.getPjNumber());
        changeEventProcessor.processAdChange(enabledChange);
    }

    /**
     * Simulates CRT change events using the exact Kafka message format.
     */
    public void simulateRealisticCrtEvents() {
        log.info("--- Simulating Realistic CRT Change Events (Exact Kafka Format) ---");

        // Test 1: Kevin Florer (BOA) joins VTM team - exact format from your Kafka example
        CrtChangeEvent teamMemberAddition = new CrtChangeEvent();
        teamMemberAddition.setCrbtId(312595);
        teamMemberAddition.setIrNumber(903422);
        teamMemberAddition.setBrNumber(29870);
        teamMemberAddition.setCrbtName("JOHN FRANK/ KEVIN FLORER");
        teamMemberAddition.setEffectiveBeginDate(LocalDate.of(2025, 5, 12));
        teamMemberAddition.setEffectiveEndDate(null); // Active team
        teamMemberAddition.setAdvisoryLetterType("A");
        teamMemberAddition.setTeamType("VTM");
        teamMemberAddition.setDisplayNameType("L");
        teamMemberAddition.setAssistantSubTeamId(null);

        CrtChangeEvent.CrtMemberChange newMember = new CrtChangeEvent.CrtMemberChange();
        newMember.setEmployeeId("0104554"); // Kevin Florer
        newMember.setRole("BOA");
        newMember.setRoleServicePriority(null);
        newMember.setMemberEffectiveBeginDate(LocalDate.of(2025, 7, 19));
        newMember.setMemberEffectiveEndDate(null); // Active membership
        newMember.setEjcIndicator("N");

        teamMemberAddition.setMembers(newMember);

        log.info("🔄 Simulating exact Kafka format: Employee {} joins team {} as {}",
                newMember.getEmployeeId(), teamMemberAddition.getCrbtId(), newMember.getRole());
        changeEventProcessor.processCrtChange(teamMemberAddition);

        // Test 2: Role change - David Chen becomes LEAD
        CrtChangeEvent roleChange = new CrtChangeEvent();
        roleChange.setCrbtId(312595);
        roleChange.setIrNumber(903422);
        roleChange.setBrNumber(29870);
        roleChange.setCrbtName("JOHN FRANK/ KEVIN FLORER");
        roleChange.setEffectiveBeginDate(LocalDate.of(2025, 5, 12));
        roleChange.setEffectiveEndDate(null);
        roleChange.setAdvisoryLetterType("A");
        roleChange.setTeamType("ACM"); // Changed to ACM for testing
        roleChange.setDisplayNameType("L");
        roleChange.setAssistantSubTeamId(null);

        CrtChangeEvent.CrtMemberChange roleChangeMember = new CrtChangeEvent.CrtMemberChange();
        roleChangeMember.setEmployeeId("0087654"); // David Chen
        roleChangeMember.setRole("LEAD"); // Promoted to LEAD
        roleChangeMember.setRoleServicePriority(null);
        roleChangeMember.setMemberEffectiveBeginDate(LocalDate.of(2025, 7, 20));
        roleChangeMember.setMemberEffectiveEndDate(null);
        roleChangeMember.setEjcIndicator("N");

        roleChange.setMembers(roleChangeMember);

        log.info("🔄 Simulating role change: Employee {} -> {} in team {} (exact Kafka format)",
                roleChangeMember.getEmployeeId(), roleChangeMember.getRole(), roleChange.getCrbtId());
        changeEventProcessor.processCrtChange(roleChange);

        // Test 3: Member leaves team - John Frank membership ends
        CrtChangeEvent memberLeaving = new CrtChangeEvent();
        memberLeaving.setCrbtId(312595);
        memberLeaving.setIrNumber(903422);
        memberLeaving.setBrNumber(29870);
        memberLeaving.setCrbtName("JOHN FRANK/ KEVIN FLORER");
        memberLeaving.setEffectiveBeginDate(LocalDate.of(2025, 5, 12));
        memberLeaving.setEffectiveEndDate(null);
        memberLeaving.setAdvisoryLetterType("A");
        memberLeaving.setTeamType("VTM");
        memberLeaving.setDisplayNameType("L");
        memberLeaving.setAssistantSubTeamId(null);

        CrtChangeEvent.CrtMemberChange leavingMember = new CrtChangeEvent.CrtMemberChange();
        leavingMember.setEmployeeId("0104553"); // John Frank
        leavingMember.setRole("FA");
        leavingMember.setRoleServicePriority(null);
        leavingMember.setMemberEffectiveBeginDate(LocalDate.of(2025, 5, 12));
        leavingMember.setMemberEffectiveEndDate(LocalDate.of(2025, 7, 20)); // Membership ends
        leavingMember.setEjcIndicator("Y"); // Leaving indicator

        memberLeaving.setMembers(leavingMember);

        log.info("🔄 Simulating member leaving: Employee {} leaves team {} (ejcInd=Y, exact Kafka format)",
                leavingMember.getEmployeeId(), memberLeaving.getCrbtId());
        changeEventProcessor.processCrtChange(memberLeaving);

        // Test 4: Team deactivation with exact Kafka format
        CrtChangeEvent teamDeactivation = new CrtChangeEvent();
        teamDeactivation.setCrbtId(312595);
        teamDeactivation.setIrNumber(903422);
        teamDeactivation.setBrNumber(29870);
        teamDeactivation.setCrbtName("JOHN FRANK/ KEVIN FLORER");
        teamDeactivation.setEffectiveBeginDate(LocalDate.of(2025, 5, 12));
        teamDeactivation.setEffectiveEndDate(LocalDate.of(2025, 7, 20)); // Team ends
        teamDeactivation.setAdvisoryLetterType("A");
        teamDeactivation.setTeamType("VTM");
        teamDeactivation.setDisplayNameType("L");
        teamDeactivation.setAssistantSubTeamId(null);

        CrtChangeEvent.CrtMemberChange finalMember = new CrtChangeEvent.CrtMemberChange();
        finalMember.setEmployeeId("0104554"); // Kevin Florer
        finalMember.setRole("BOA");
        finalMember.setRoleServicePriority(null);
        finalMember.setMemberEffectiveBeginDate(LocalDate.of(2025, 5, 12));
        finalMember.setMemberEffectiveEndDate(LocalDate.of(2025, 7, 20)); // Final membership ends
        finalMember.setEjcIndicator("Y");

        teamDeactivation.setMembers(finalMember);

        log.info("🔄 Simulating team deactivation: Team {} deactivated (effEndDa set, exact Kafka format)",
                teamDeactivation.getCrbtId());
        changeEventProcessor.processCrtChange(teamDeactivation);
    }

    /**
     * Manual trigger for testing specific scenarios during development.
     * Now uses realistic data for all scenarios.
     */
    public void triggerSpecificScenario(String scenarioName) {
        log.info("Triggering realistic test scenario: {}", scenarioName);

        switch (scenarioName.toLowerCase()) {
            case "manager_change":
                simulateRealisticAdEvents();
                break;
            case "team_change":
                simulateRealisticCrtEvents();
                break;
            case "all":
                simulateRealisticAdEvents();
                try { Thread.sleep(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                simulateRealisticCrtEvents();
                break;
            case "leadership_impact":
                // Test specific scenario: leader change affecting direct reports
                triggerLeadershipImpactTest();
                break;
            default:
                log.warn("Unknown scenario: {}. Available: manager_change, team_change, all, leadership_impact", scenarioName);
        }
    }

    /**
     * Specific test for leadership hierarchy impact analysis.
     */
    private void triggerLeadershipImpactTest() {
        log.info("🔬 Testing leadership hierarchy impact analysis");

        // Change Maria Garcia's title (she has direct reports: John Frank and Kevin Florer)
        AdChangeEvent leaderTitleChange = new AdChangeEvent();
        leaderTitleChange.setPjNumber("j050001"); // Maria Garcia
        leaderTitleChange.setChangeType("UPDATE");
        leaderTitleChange.setProperty("Title");
        leaderTitleChange.setBeforeValue("Branch Team Support Leader");
        leaderTitleChange.setNewValue("Senior Branch Team Support Leader");

        log.info("🔄 Testing impact of leader title change: {} (should affect direct reports)",
                leaderTitleChange.getPjNumber());
        changeEventProcessor.processAdChange(leaderTitleChange);
    }
}
