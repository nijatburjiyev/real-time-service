package com.edwardjones.cre.model.domain;

import jakarta.persistence.*;
import lombok.Data;
import java.util.Set;

@Data
@Entity
public class AppUser {
    @Id
    private String username;
    private String employeeId;
    private String firstName;
    private String lastName;
    private String title;
    @Column(length = 1024)
    private String distinguishedName;
    private String managerUsername;
    private boolean isActive = true;

    // This is a derived/calculated field, not directly mapped to a column
    @Transient
    private String calculatedVisibilityProfile;
    @Transient
    private Set<String> calculatedGroups;
}
