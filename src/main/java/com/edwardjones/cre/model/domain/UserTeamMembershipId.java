package com.edwardjones.cre.model.domain;

import jakarta.persistence.Embeddable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import java.io.Serializable;

@Embeddable
@Getter
@Setter
@EqualsAndHashCode
public class UserTeamMembershipId implements Serializable {
    private String userUsername;
    private Integer teamCrbtId;

    public UserTeamMembershipId() {
    }

    public UserTeamMembershipId(String userUsername, Integer teamCrbtId) {
        this.userUsername = userUsername;
        this.teamCrbtId = teamCrbtId;
    }
}
