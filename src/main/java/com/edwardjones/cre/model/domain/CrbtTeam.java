package com.edwardjones.cre.model.domain;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
public class CrbtTeam {
    @Id
    private Integer crbtId;
    private String teamName;
    private String teamType;
    private String ownerFaNo; // Added to support business logic for branch leader groups
    private boolean isActive = true;
}
