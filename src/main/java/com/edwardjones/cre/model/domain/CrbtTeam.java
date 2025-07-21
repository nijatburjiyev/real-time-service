package com.edwardjones.cre.model.domain;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Entity
@Table(name = "crbt_teams")
@Data
@NoArgsConstructor
public class CrbtTeam {

    @Id
    @Column(name = "crbt_id", nullable = false)
    private Integer crbtId;

    @Column(name = "ir_number")
    private Integer irNumber;

    @Column(name = "br_number")
    private Integer brNumber;

    @Column(name = "team_name", nullable = false)
    private String teamName;

    @Column(name = "team_type")
    private String teamType;

    @Column(name = "advisory_letter_type")
    private String advisoryLetterType;

    @Column(name = "display_name_type")
    private String displayNameType;

    @Column(name = "assistant_sub_team_id")
    private Integer assistantSubTeamId;

    @Column(name = "effective_start_date")
    private LocalDate effectiveStartDate;

    @Column(name = "effective_end_date")
    private LocalDate effectiveEndDate;

    @Column(name = "active")
    private boolean active = true;

    public CrbtTeam(Integer crbtId, String teamName, String teamType, boolean active) {
        this.crbtId = crbtId;
        this.teamName = teamName;
        this.teamType = teamType;
        this.active = active;
        this.effectiveStartDate = LocalDate.now();
    }
}
