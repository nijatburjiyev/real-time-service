package com.edwardjones.cre.model.domain;

import jakarta.persistence.*;
import lombok.Data;
import java.util.HashSet;
import java.util.Set;

@Data
@Entity
public class AppUser {
    @Id
    private String username;

    @Column(unique = true) // Add unique constraint for performance and integrity
    private String employeeId;

    private String firstName;
    private String lastName;
    private String title;

    @Column(length = 1024)
    private String distinguishedName;

    private String country;
    private boolean isActive = true;

    // --- The Recommended Hybrid Manager Relationship ---
    @Column(name = "manager_username")
    private String managerUsername;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "manager_username", referencedColumnName = "username", insertable = false, updatable = false)
    private AppUser manager;

    @OneToMany(mappedBy = "manager", fetch = FetchType.LAZY)
    private Set<AppUser> directReports = new HashSet<>();

    // Relationship to team memberships - using Set to avoid duplicates
    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private Set<UserTeamMembership> teamMemberships = new HashSet<>();

    // Calculated fields for business logic results (not persisted)
    @Transient
    private String calculatedVisibilityProfile;

    @Transient
    private Set<String> calculatedGroups;
}
