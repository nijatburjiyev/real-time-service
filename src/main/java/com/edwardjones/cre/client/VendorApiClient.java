package com.edwardjones.cre.client;

import com.edwardjones.cre.model.domain.AppUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class VendorApiClient {

    // Counter to simulate API call tracking
    private final AtomicInteger updateCount = new AtomicInteger(0);
    private final AtomicInteger createCount = new AtomicInteger(0);
    private final AtomicInteger groupUpdateCount = new AtomicInteger(0);

    // Placeholder classes for vendor data structures
    public static class VendorUser {
        private String username;
        private String visibilityProfile;
        private List<String> groups;

        public VendorUser(String username, String visibilityProfile, List<String> groups) {
            this.username = username;
            this.visibilityProfile = visibilityProfile;
            this.groups = groups;
        }

        // Getters for logging
        public String getUsername() { return username; }
        public String getVisibilityProfile() { return visibilityProfile; }
        public List<String> getGroups() { return groups; }
    }

    public static class VendorGroup {
        private String groupName;
        private List<String> members;

        public VendorGroup(String groupName, List<String> members) {
            this.groupName = groupName;
            this.members = members;
        }

        public String getGroupName() { return groupName; }
        public List<String> getMembers() { return members; }
    }

    public static class VendorVisibilityProfile {
        private String profileName;
        private String description;

        public VendorVisibilityProfile(String profileName, String description) {
            this.profileName = profileName;
            this.description = description;
        }

        public String getProfileName() { return profileName; }
        public String getDescription() { return description; }
    }

    // Methods for reconciliation - simulate fetching current vendor state
    public List<VendorUser> getAllUsers() {
        log.info("MOCK VENDOR API - Fetching all users from vendor system");

        List<VendorUser> mockVendorUsers = new ArrayList<>();
        // Simulate some existing users in vendor system that might be out of sync
        mockVendorUsers.add(new VendorUser("p12345", "Vis-US-HO-Leader", List.of("US Home Office Submitters")));
        mockVendorUsers.add(new VendorUser("p23456", "Vis-US-HO-Old", List.of("Old Group Name"))); // Out of sync
        mockVendorUsers.add(new VendorUser("p34567", "Vis-US-BR", List.of("US Field Submitters")));

        log.info("MOCK VENDOR API - Returned {} users from vendor", mockVendorUsers.size());
        return mockVendorUsers;
    }

    public List<VendorGroup> getAllGroups() {
        log.info("MOCK VENDOR API - Fetching all groups from vendor system");

        List<VendorGroup> mockGroups = new ArrayList<>();
        mockGroups.add(new VendorGroup("US Home Office Submitters", List.of("p12345", "p23456")));
        mockGroups.add(new VendorGroup("US Field Submitters", List.of("p34567", "p67890")));
        mockGroups.add(new VendorGroup("CAN Home Office Submitters", List.of("p56789")));

        log.info("MOCK VENDOR API - Returned {} groups from vendor", mockGroups.size());
        return mockGroups;
    }

    public List<VendorVisibilityProfile> getAllVisibilityProfiles() {
        log.info("MOCK VENDOR API - Fetching all visibility profiles from vendor system");

        List<VendorVisibilityProfile> mockProfiles = new ArrayList<>();
        mockProfiles.add(new VendorVisibilityProfile("Vis-US-HO", "US Home Office Profile"));
        mockProfiles.add(new VendorVisibilityProfile("Vis-US-BR", "US Branch Profile"));
        mockProfiles.add(new VendorVisibilityProfile("Vis-CA-HO", "Canadian Home Office Profile"));

        log.info("MOCK VENDOR API - Returned {} visibility profiles from vendor", mockProfiles.size());
        return mockProfiles;
    }

    // Methods for real-time updates - simulate API calls with detailed logging
    public void updateUser(AppUser user) {
        int count = updateCount.incrementAndGet();

        log.info("📤 MOCK VENDOR API CALL #{} - UPDATE USER", count);
        log.info("   → Username: {}", user.getUsername());
        log.info("   → Name: {} {}", user.getFirstName(), user.getLastName());
        log.info("   → Calculated VP: {}", user.getCalculatedVisibilityProfile());
        log.info("   → Calculated Groups: {}", user.getCalculatedGroups());
        log.info("   → Country: {}", user.getCountry());
        log.info("   → Manager: {}", user.getManagerUsername());
        log.info("✅ VENDOR UPDATE COMPLETE for {}", user.getUsername());

        // Simulate network delay
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void createUser(AppUser user) {
        int count = createCount.incrementAndGet();

        log.info("📤 MOCK VENDOR API CALL #{} - CREATE USER", count);
        log.info("   → Creating new user: {} ({})", user.getUsername(), user.getEmployeeId());
        log.info("   → Initial VP: {}", user.getCalculatedVisibilityProfile());
        log.info("   → Initial Groups: {}", user.getCalculatedGroups());
        log.info("✅ VENDOR CREATE COMPLETE for {}", user.getUsername());
    }

    public void updateGroup(Object group) {
        int count = groupUpdateCount.incrementAndGet();

        log.info("📤 MOCK VENDOR API CALL #{} - UPDATE GROUP", count);
        log.info("   → Group object: {}", group);
        log.info("✅ VENDOR GROUP UPDATE COMPLETE");
    }

    public void updateVisibilityProfile(Object vp) {
        log.info("📤 MOCK VENDOR API CALL - UPDATE VISIBILITY PROFILE");
        log.info("   → VP object: {}", vp);
        log.info("✅ VENDOR VP UPDATE COMPLETE");
    }

    // Utility methods for testing
    public void resetCounters() {
        updateCount.set(0);
        createCount.set(0);
        groupUpdateCount.set(0);
        log.info("🔄 Vendor API call counters reset");
    }

    public String getCallStatistics() {
        return String.format("Vendor API Calls - Updates: %d, Creates: %d, Group Updates: %d",
                updateCount.get(), createCount.get(), groupUpdateCount.get());
    }
}
