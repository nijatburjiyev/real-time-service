package com.edwardjones.cre.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class CrtChangeEvent {
    @JsonProperty("crbtId")
    private Integer crbtId;

    @JsonProperty("irNo")
    private Integer irNumber;

    @JsonProperty("brNo")
    private Integer brNumber;

    @JsonProperty("crbtNa")
    private String crbtName;

    @JsonProperty("effBegDa")
    private String effectiveBeginDate; // "2025-05-12" format

    @JsonProperty("effEndDa")
    private String effectiveEndDate; // "2025-05-12" format or null

    @JsonProperty("advsryLtrIrTyCd")
    private String advisoryLetterType;

    @JsonProperty("crbtTeamTyCd")
    private String teamType;

    @JsonProperty("irDsplyNaTyCd")
    private String displayNameType;

    @JsonProperty("astSubTeamId")
    private Integer assistantSubTeamId;

    @JsonProperty("members")
    private CrtMemberChange members; // Single object, not array

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CrtMemberChange {
        @JsonProperty("emplId")
        private String employeeId;

        @JsonProperty("crbtRoleCd")
        private String role;

        @JsonProperty("roleSvcPriCd")
        private String rolePriority;

        @JsonProperty("effBegDa")
        private String effectiveBeginDate; // "2025-07-19" format

        @JsonProperty("effEndDa")
        private String effectiveEndDate; // "2025-07-19" format or null

        @JsonProperty("ejcInd")
        private String ejcIndicator;
    }

    /**
     * Checks if this is a team membership change (has member data)
     */
    public boolean isMembershipChange() {
        return members != null && members.getEmployeeId() != null;
    }

    /**
     * Checks if this is a team metadata change (team info without member changes)
     */
    public boolean isTeamMetadataChange() {
        return members == null || members.getEmployeeId() == null;
    }

    /**
     * Gets the member's employee ID for database lookups
     */
    public String getMemberEmployeeId() {
        return members != null ? members.getEmployeeId() : null;
    }

    /**
     * Checks if this event represents a team deactivation
     */
    public boolean isTeamDeactivated() {
        return effectiveEndDate != null && !effectiveEndDate.trim().isEmpty();
    }

    /**
     * Checks if this event represents a member leaving the team
     */
    public boolean isMemberLeaving() {
        return members != null && members.getEffectiveEndDate() != null && !members.getEffectiveEndDate().trim().isEmpty();
    }

    /**
     * Checks if this is a managerial change (team leadership change)
     */
    public boolean isManagerialChange() {
        return members != null && "LEAD".equalsIgnoreCase(members.getRole());
    }
}
