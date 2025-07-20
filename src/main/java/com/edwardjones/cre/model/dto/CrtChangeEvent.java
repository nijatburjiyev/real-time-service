package com.edwardjones.cre.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.time.LocalDate;

@Data
@JsonIgnoreProperties(ignoreUnknown = true) // Safely ignore fields we don't need
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
    private LocalDate effectiveBeginDate;

    @JsonProperty("effEndDa")
    private LocalDate effectiveEndDate;

    @JsonProperty("advsryLtrIrTyCd")
    private String advisoryLetterType;

    @JsonProperty("crbtTeamTyCd") // Updated to match exact Kafka field name
    private String teamType;

    @JsonProperty("irDsplyNaTyCd")
    private String displayNameType;

    @JsonProperty("astSubTeamId")
    private Integer assistantSubTeamId;

    @JsonProperty("members")
    private CrtMemberChange members; // Single object as shown in your Kafka example

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CrtMemberChange {
        @JsonProperty("emplId")
        private String employeeId;

        @JsonProperty("crbtRoleCd") // Updated to match exact Kafka field name
        private String role;

        @JsonProperty("roleSvcPriCd")
        private String roleServicePriority;

        @JsonProperty("effBegDa")
        private LocalDate memberEffectiveBeginDate;

        @JsonProperty("effEndDa")
        private LocalDate memberEffectiveEndDate;

        @JsonProperty("ejcInd")
        private String ejcIndicator;
    }

    /**
     * Helper to determine if the team itself has been deactivated.
     * @return true if the top-level effectiveEndDate is not null.
     */
    public boolean isTeamDeactivated() {
        return effectiveEndDate != null;
    }

    /**
     * Helper to determine if this is a managerial change that could impact hierarchies.
     * @return true if the role is BOA or other leadership roles.
     */
    public boolean isManagerialChange() {
        return members != null &&
               ("BOA".equalsIgnoreCase(members.getRole()) ||
                "LEAD".equalsIgnoreCase(members.getRole()) ||
                "FA".equalsIgnoreCase(members.getRole()) ||
                "VTM".equalsIgnoreCase(teamType) ||
                "HTM".equalsIgnoreCase(teamType) ||
                "SFA".equalsIgnoreCase(teamType));
    }

    /**
     * Helper to determine if this member is leaving the team.
     * @return true if the member's effective end date is set or ejcInd is "Y"
     */
    public boolean isMemberLeaving() {
        return members != null &&
               (members.getMemberEffectiveEndDate() != null ||
                "Y".equalsIgnoreCase(members.getEjcIndicator()));
    }
}
