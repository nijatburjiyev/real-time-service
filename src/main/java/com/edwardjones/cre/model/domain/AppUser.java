package com.edwardjones.cre.model.domain;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.Set;

@Entity
@Table(name = "app_users")
@Data
@NoArgsConstructor
public class AppUser {

    @Id
    @Column(name = "username", nullable = false)
    private String username;

    @Column(name = "employee_id", unique = true)
    private String employeeId;

    @Column(name = "first_name")
    private String firstName;

    @Column(name = "last_name")
    private String lastName;

    @Column(name = "title")
    private String title;

    @Column(name = "manager_username")
    private String managerUsername;

    @Column(name = "distinguished_name", length = 500)
    private String distinguishedName;

    @Column(name = "country")
    private String country;

    @Column(name = "active")
    private boolean active = true;

    @Column(name = "hire_date")
    private LocalDate hireDate;

    @Column(name = "last_updated")
    private LocalDate lastUpdated;

    // Transient fields for calculated compliance configuration
    @Transient
    private String calculatedVisibilityProfile;

    @Transient
    private Set<String> calculatedGroups;

    public AppUser(String username, String employeeId, String title, String managerUsername,
                  String distinguishedName, String country, boolean active) {
        this.username = username;
        this.employeeId = employeeId;
        this.title = title;
        this.managerUsername = managerUsername;
        this.distinguishedName = distinguishedName;
        this.country = country;
        this.active = active;
        this.lastUpdated = LocalDate.now();
    }
}
