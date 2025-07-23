package com.edwardjones.cre.client.impl;

import com.edwardjones.cre.client.VendorApiClient;
import com.edwardjones.cre.model.dto.DesiredConfiguration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@Profile("test | bootstrap-test")
public class MockVendorApiClient implements VendorApiClient {

    private final AtomicInteger updateCount = new AtomicInteger(0);
    private final AtomicInteger createCount = new AtomicInteger(0);

    @Override
    public List<VendorUser> getAllUsers() {
        log.info("MOCK VENDOR API - Fetching all users from vendor system");

        List<VendorUser> mockUsers = new ArrayList<>();
        mockUsers.add(new VendorUser("p12345", "Vis-US-HO-Leader",
            List.of(new VendorUser.GroupRef("US Home Office Submitters")), true));
        mockUsers.add(new VendorUser("p23456", "Vis-US-HO-Old",
            List.of(new VendorUser.GroupRef("Old Group Name")), true));

        return mockUsers;
    }

    @Override
    public List<VendorGroup> getAllGroups() {
        log.info("MOCK VENDOR API - Fetching all groups from vendor system");

        List<VendorGroup> mockGroups = new ArrayList<>();
        mockGroups.add(new VendorGroup("US Home Office Submitters", List.of("p12345", "p23456")));
        mockGroups.add(new VendorGroup("US Field Submitters", List.of("p34567", "p67890")));

        return mockGroups;
    }

    @Override
    public List<VendorVisibilityProfile> getAllVisibilityProfiles() {
        log.info("MOCK VENDOR API - Fetching all visibility profiles from vendor system");

        List<VendorVisibilityProfile> mockProfiles = new ArrayList<>();
        mockProfiles.add(new VendorVisibilityProfile("Vis-US-HO", "US Home Office Profile"));
        mockProfiles.add(new VendorVisibilityProfile("Vis-US-BR", "US Branch Profile"));

        return mockProfiles;
    }

    @Override
    public void updateUser(DesiredConfiguration config) {
        int count = updateCount.incrementAndGet();

        log.info("📤 MOCK VENDOR API CALL #{} - UPDATE USER", count);
        log.info("   → Username: {}", config.username());
        log.info("   → Name: {} {}", config.firstName(), config.lastName());
        log.info("   → Calculated VP: {}", config.visibilityProfile());
        log.info("   → Calculated Groups: {}", config.groups());
        log.info("   → Active: {}", config.isActive());
        log.info("✅ VENDOR UPDATE COMPLETE for {}", config.username());

        // Simulate network delay
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void createUser(DesiredConfiguration config) {
        int count = createCount.incrementAndGet();

        log.info("📤 MOCK VENDOR API CALL #{} - CREATE USER", count);
        log.info("   → Creating new user: {}", config.username());
        log.info("   → Initial VP: {}", config.visibilityProfile());
        log.info("   → Initial Groups: {}", config.groups());
        log.info("✅ VENDOR CREATE COMPLETE for {}", config.username());
    }
}
