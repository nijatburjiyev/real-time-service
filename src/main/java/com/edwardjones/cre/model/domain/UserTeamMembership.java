package com.edwardjones.cre.model.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDate;

@Getter
@Setter
@Entity
@Table(name = "USER_TEAM_MEMBERSHIP")
public class UserTeamMembership {

    @EmbeddedId
    private UserTeamMembershipId id = new UserTeamMembershipId();

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("userUsername") // This maps the 'userUsername' part of the composite ID
    @JoinColumn(name = "user_username")
    private AppUser user;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("teamCrbtId") // This maps the 'teamCrbtId' part of the composite ID
    @JoinColumn(name = "team_crbt_id")
    private CrbtTeam team;

    @Column(name = "member_role", length = 20)
    private String memberRole;

    @Column(name = "effective_start_date")
    private LocalDate effectiveStartDate;

    @Column(name = "effective_end_date")
    private LocalDate effectiveEndDate;

    // Convenience constructor
    public UserTeamMembership(AppUser user, CrbtTeam team) {
        this.user = user;
        this.team = team;
        this.id.setUserUsername(user.getUsername());
        this.id.setTeamCrbtId(team.getCrbtId());
    }

    public UserTeamMembership() {
    }
}
