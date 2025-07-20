package com.edwardjones.cre.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true) // Safely ignore any fields in the JSON we don't care about
public class AdChangeEvent {
    @JsonProperty("PJNumber")
    private String pjNumber;

    @JsonProperty("ChangeType")
    private String changeType;

    @JsonProperty("Property")
    private String property;

    @JsonProperty("BeforeValue")
    private String beforeValue;

    @JsonProperty("NewValue")
    private String newValue;

    /**
     * Checks if the change is related to the user's manager or direct reports.
     * @return true if the property is 'Manager' or 'directReports'
     */
    public boolean isManagerialChange() {
        return "Manager".equalsIgnoreCase(property) || "directReports".equalsIgnoreCase(property);
    }

    /**
     * Checks if this change could impact a user's calculated configuration.
     * @return true if the property affects compliance calculations
     */
    public boolean isImpactfulChange() {
        return "Title".equalsIgnoreCase(property) ||
               "DistinguishedName".equalsIgnoreCase(property) ||
               isManagerialChange();
    }
}
