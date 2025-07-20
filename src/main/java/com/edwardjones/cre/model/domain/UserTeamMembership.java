package com.edwardjones.cre.model.domain;

import jakarta.persistence.*;
import lombok.Data;
import java.io.Serializable;
import java.time.LocalDate;

@Data
@Entity
@IdClass(UserTeamMembership.MembershipId.class)
public class UserTeamMembership {
    @Id
    private String userUsername;
    @Id
    private Integer teamCrbtId;

    private String memberRole;
    private LocalDate effectiveStartDate;
    private LocalDate effectiveEndDate;

    // Composite Primary Key Class
    @Data
    public static class MembershipId implements Serializable {
        private String userUsername;
        private Integer teamCrbtId;
    }
}
