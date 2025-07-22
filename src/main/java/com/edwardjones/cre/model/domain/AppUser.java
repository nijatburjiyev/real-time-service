package com.edwardjones.cre.model.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.util.HashSet;
import java.util.Set;
import java.util.Objects;

/**
 * Pure JPA entity representing an application user.
 *
 * This entity contains only persistent state - no calculated fields.
 * Calculation results are returned via DesiredConfiguration DTO.
 */
@Getter
@Setter
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
    private String state; // Added to support business logic for generating correct group names
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

    // Custom equals and hashCode to prevent Hibernate circular dependency issues
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AppUser appUser = (AppUser) o;
        return Objects.equals(username, appUser.username);
    }

    @Override
    public int hashCode() {
        return Objects.hash(username);
    }

    @Override
    public String toString() {
        return "AppUser{" +
                "username='" + username + '\'' +
                ", employeeId='" + employeeId + '\'' +
                ", firstName='" + firstName + '\'' +
                ", lastName='" + lastName + '\'' +
                ", title='" + title + '\'' +
                ", country='" + country + '\'' +
                ", state='" + state + '\'' +
                ", isActive=" + isActive +
                ", managerUsername='" + managerUsername + '\'' +
                '}';
    }
}
