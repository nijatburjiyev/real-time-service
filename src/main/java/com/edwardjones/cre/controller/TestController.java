package com.edwardjones.cre.controller;

import com.edwardjones.cre.client.AdLdapClient;
import com.edwardjones.cre.client.CrbtApiClient;
import com.edwardjones.cre.model.domain.AppUser;
import com.edwardjones.cre.model.domain.CrbtTeam;
import com.edwardjones.cre.model.dto.AdChangeEvent;
import com.edwardjones.cre.model.dto.CrtChangeEvent;
import com.edwardjones.cre.repository.AppUserRepository;
import com.edwardjones.cre.repository.CrbtTeamRepository;
import com.edwardjones.cre.service.realtime.ChangeEventProcessor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Test controller for manually triggering mock scenarios and loading data.
 * This controller is only active when the application is run with the 'local' profile.
 */
@Slf4j
@RestController
@RequestMapping("/api/test")
@Profile("local")
public class TestController {

    private final ChangeEventProcessor changeEventProcessor;
    private final AdLdapClient adLdapClient;
    private final CrbtApiClient crbtApiClient;
    private final AppUserRepository appUserRepository;
    private final CrbtTeamRepository crbtTeamRepository;

    @Autowired
    public TestController(ChangeEventProcessor changeEventProcessor,
                          AdLdapClient adLdapClient,
                          CrbtApiClient crbtApiClient,
                          AppUserRepository appUserRepository,
                          CrbtTeamRepository crbtTeamRepository) {
        this.changeEventProcessor = changeEventProcessor;
        this.adLdapClient = adLdapClient;
        this.crbtApiClient = crbtApiClient;
        this.appUserRepository = appUserRepository;
        this.crbtTeamRepository = crbtTeamRepository;
    }

    /**
     * Simulates receiving an AD Change event from Kafka.
     * The request body should be a JSON object matching the AdChangeEvent DTO.
     */
    @PostMapping("/simulate/ad-change")
    @Transactional
    public ResponseEntity<Map<String, Object>> simulateAdChangeEvent(@RequestBody AdChangeEvent event) {
        log.info("▶️ Manual Trigger: Simulating AD Change Event for user '{}'", event.getPjNumber());
        Map<String, Object> response = new HashMap<>();
        try {
            changeEventProcessor.processAdChange(event);
            response.put("status", "success");
            response.put("message", "AD Change Event processed successfully for user: " + event.getPjNumber());
            appUserRepository.findById(event.getPjNumber()).ifPresent(user -> response.put("updatedUser", user));
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error processing simulated AD event", e);
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Simulates receiving a CRT Change event from Kafka.
     * The request body should be a JSON object matching the CrtChangeEvent DTO.
     */
    @PostMapping("/simulate/crt-change")
    @Transactional
    public ResponseEntity<Map<String, Object>> simulateCrtChangeEvent(@RequestBody CrtChangeEvent event) {
        log.info("▶️ Manual Trigger: Simulating CRT Change Event for team '{}'", event.getCrbtId());
        Map<String, Object> response = new HashMap<>();
        try {
            changeEventProcessor.processCrtChange(event);
            response.put("status", "success");
            response.put("message", "CRT Change Event processed successfully for team: " + event.getCrbtId());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error processing simulated CRT event", e);
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Loads all users from the AD client (uses existing JSON data).
     * This simulates a bulk data load from Active Directory.
     */
    @PostMapping("/load/all-ad-users")
    @Transactional
    public ResponseEntity<Map<String, Object>> loadAllAdUsers() {
        log.info("▶️ Manual Trigger: Loading all users from AD client");
        Map<String, Object> response = new HashMap<>();
        try {
            List<AppUser> users = adLdapClient.fetchAllUsers();
            appUserRepository.saveAll(users);
            response.put("status", "success");
            response.put("message", "Loaded " + users.size() + " users from AD data");
            response.put("userCount", users.size());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error loading AD users", e);
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Manually creates a single user from provided JSON data.
     */
    @PostMapping("/load/single-user")
    @Transactional
    public ResponseEntity<Map<String, Object>> loadSingleUser(@RequestBody AppUser user) {
        log.info("▶️ Manual Trigger: Loading single user '{}'", user.getUsername());
        Map<String, Object> response = new HashMap<>();
        try {
            AppUser savedUser = appUserRepository.save(user);
            response.put("status", "success");
            response.put("message", "User " + savedUser.getUsername() + " loaded successfully");
            response.put("loadedUser", savedUser);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error loading single user", e);
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Loads all teams from the CRBT client (uses existing JSON data).
     * This simulates a bulk data load from the CRBT API.
     */
    @PostMapping("/load/all-crbt-teams")
    @Transactional
    public ResponseEntity<Map<String, Object>> loadAllCrbtTeams() {
        log.info("▶️ Manual Trigger: Loading all teams from CRBT client");
        Map<String, Object> response = new HashMap<>();
        try {
            List<CrbtTeam> teams = crbtApiClient.fetchAllTeams();
            crbtTeamRepository.saveAll(teams);
            response.put("status", "success");
            response.put("message", "Loaded " + teams.size() + " teams from CRBT data");
            response.put("teamCount", teams.size());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error loading CRBT teams", e);
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Manually creates a single team from provided JSON data.
     */
    @PostMapping("/load/single-team")
    @Transactional
    public ResponseEntity<Map<String, Object>> loadSingleTeam(@RequestBody CrbtTeam team) {
        log.info("▶️ Manual Trigger: Loading single team '{}'", team.getCrbtId());
        Map<String, Object> response = new HashMap<>();
        try {
            CrbtTeam savedTeam = crbtTeamRepository.save(team);
            response.put("status", "success");
            response.put("message", "Team " + savedTeam.getCrbtId() + " loaded successfully");
            response.put("loadedTeam", savedTeam);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error loading single team", e);
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Gets current database status for debugging.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> status = new HashMap<>();

        long userCount = appUserRepository.count();
        long teamCount = crbtTeamRepository.count();

        status.put("userCount", userCount);
        status.put("teamCount", teamCount);
        status.put("status", "ready");
        status.put("message", "TestController is active under 'local' profile");

        log.info("📊 Status Check: {} users, {} teams in database", userCount, teamCount);
        return ResponseEntity.ok(status);
    }
}
