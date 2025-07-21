package com.edwardjones.cre.model.dto;

import lombok.Data;
import java.util.List;

/**
 * DTO for CRT API response containing team details with full member list.
 * This is used in Phase 3 of the bulk processing workflow.
 */
@Data
public class CrbtTeamWithMembers {
    private String crbtId;
    private String teamName;
    private String teamType; // VTM, HTM, SFA
    private List<String> memberUsernames;
    private String ownerUsername;
    private boolean active;
}
