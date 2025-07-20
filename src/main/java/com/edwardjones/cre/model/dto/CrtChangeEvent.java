package com.edwardjones.cre.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.time.LocalDate;

@Data
@JsonIgnoreProperties(ignoreUnknown = true) // Safely ignore fields we don't need
public class CrtChangeEvent {
    @JsonProperty("crbtId") // Changed from "cIbtId" to match actual message
    private Integer crbtId;

    @JsonProperty("irNo")
    private Integer irNumber;

    @JsonProperty("brNo") // Changed from "bINo" to match actual message
    private Integer brNumber;

    @JsonProperty("crbtNa")
    private String crbtName;

    @JsonProperty("effBegDa")
    private LocalDate effectiveBeginDate;

    @JsonProperty("effEndDa")
    private LocalDate effectiveEndDate;

    @JsonProperty("advsryLtrIrTyCd")
    private String advisoryLetterType;

    @JsonProperty("crbtTeamTyCd")
    private String teamType;

    @JsonProperty("irDsplyNaTyCd")
    private String displayNameType;

    @JsonProperty("astSubTeamId")
    private Integer assistantSubTeamId;

    @JsonProperty("members")
    private CrtMemberChange members; // Single object as shown in actual message

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CrtMemberChange {
        @JsonProperty("emplId")
        private String employeeId;

        @JsonProperty("crbtRoleCd") // Changed from "erbtRoleCd" to match actual message
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
                "VTM".equalsIgnoreCase(teamType) ||
                "HTM".equalsIgnoreCase(teamType) ||
                "ACM".equalsIgnoreCase(teamType)); // Added ACM as shown in your example
    }
}
