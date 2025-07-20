package com.edwardjones.cre.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.time.LocalDate;
import java.util.List;

@Data
public class CrtChangeEvent {
    @JsonProperty("cIbtId")
    private Integer crbtId;
    @JsonProperty("crbtTeamTyCd")
    private String teamType;
    @JsonProperty("effEndDa")
    private LocalDate effectiveEndDate;
    @JsonProperty("members")
    private List<CrtMemberChange> members;

    @Data
    public static class CrtMemberChange {
        @JsonProperty("emplId")
        private String employeeId;
        @JsonProperty("crbtRolecd")
        private String role;
        @JsonProperty("effEndDa")
        private LocalDate memberEffectiveEndDate;
    }
}
