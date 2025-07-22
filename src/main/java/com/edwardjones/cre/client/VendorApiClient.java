package com.edwardjones.cre.client;

import com.edwardjones.cre.model.dto.DesiredConfiguration;
import java.util.List;

/**
 * Interface for vendor API operations.
 * Allows swapping between mock and production implementations.
 */
public interface VendorApiClient {

    // Reconciliation methods - fetch current vendor state
    List<VendorUser> getAllUsers();
    List<VendorGroup> getAllGroups();
    List<VendorVisibilityProfile> getAllVisibilityProfiles();

    // Real-time update methods
    void updateUser(DesiredConfiguration config);
    void createUser(DesiredConfiguration config);

    // Placeholder classes for vendor data structures
    class VendorUser {
        private String username;
        private String visibilityProfile;
        private List<GroupRef> groups;
        private boolean isActive;

        public VendorUser(String username, String visibilityProfile, List<GroupRef> groups, boolean isActive) {
            this.username = username;
            this.visibilityProfile = visibilityProfile;
            this.groups = groups;
            this.isActive = isActive;
        }

        // Getters
        public String getUsername() { return username; }
        public String getVisibilityProfile() { return visibilityProfile; }
        public List<GroupRef> getGroups() { return groups; }
        public boolean isActive() { return isActive; }

        public static class GroupRef {
            private String groupName;

            public GroupRef(String groupName) {
                this.groupName = groupName;
            }

            public String getGroupName() { return groupName; }
        }
    }

    class VendorGroup {
        private String groupName;
        private List<String> members;

        public VendorGroup(String groupName, List<String> members) {
            this.groupName = groupName;
            this.members = members;
        }

        public String getGroupName() { return groupName; }
        public List<String> getMembers() { return members; }
    }

    class VendorVisibilityProfile {
        private String profileName;
        private String description;

        public VendorVisibilityProfile(String profileName, String description) {
            this.profileName = profileName;
            this.description = description;
        }

        public String getProfileName() { return profileName; }
        public String getDescription() { return description; }
    }
}
