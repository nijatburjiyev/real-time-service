package com.edwardjones.cre.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true) // Safely ignore any fields in the JSON we don't care about
public class AdChangeEvent {

    // Property name constants to prevent typos and improve maintainability
    public static final String PROPERTY_TITLE = "Title";
    public static final String PROPERTY_MANAGER = "Manager";
    public static final String PROPERTY_MANAGER_USERNAME = "ManagerUsername";
    public static final String PROPERTY_DISTINGUISHED_NAME = "DistinguishedName";
    public static final String PROPERTY_EJ_IR_NUMBER = "ej-IRNumber";
    public static final String PROPERTY_STATE = "State";
    public static final String PROPERTY_DIRECT_REPORTS = "directReports";
    public static final String PROPERTY_ENABLED = "Enabled";
    public static final String PROPERTY_NAME = "Name";
    public static final String PROPERTY_TEAM_ROLE = "TeamRole";

    // Change type constants
    public static final String CHANGE_TYPE_NEW_USER = "NewUser";
    public static final String CHANGE_TYPE_TERMINATED_USER = "TerminatedUser";
    public static final String CHANGE_TYPE_DATA_CHANGE = "DataChange";

    @JsonProperty("AfterDate")
    private String afterDate;

    @JsonProperty("BeforeDate")
    private String beforeDate;

    @JsonProperty("ProcessedDate")
    private String processedDate;

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
        return PROPERTY_MANAGER.equalsIgnoreCase(property) || PROPERTY_DIRECT_REPORTS.equalsIgnoreCase(property);
    }

    /**
     * Checks if this change could impact a user's calculated configuration.
     * @return true if the property affects compliance calculations
     */
    public boolean isImpactfulChange() {
        return PROPERTY_TITLE.equalsIgnoreCase(property) ||
               PROPERTY_DISTINGUISHED_NAME.equalsIgnoreCase(property) ||
               PROPERTY_EJ_IR_NUMBER.equalsIgnoreCase(property) ||
               PROPERTY_STATE.equalsIgnoreCase(property) ||
               isManagerialChange();
    }

    /**
     * Checks if this is a new user creation event.
     * @return true if the ChangeType is 'NewUser'
     */
    public boolean isNewUser() {
        return CHANGE_TYPE_NEW_USER.equalsIgnoreCase(changeType);
    }

    /**
     * Checks if this is a user termination event.
     * @return true if the ChangeType is 'TerminatedUser'
     */
    public boolean isTerminatedUser() {
        return CHANGE_TYPE_TERMINATED_USER.equalsIgnoreCase(changeType);
    }

    /**
     * Checks if this is a data change event.
     * @return true if the ChangeType is 'DataChange'
     */
    public boolean isDataChange() {
        return CHANGE_TYPE_DATA_CHANGE.equalsIgnoreCase(changeType);
    }
}
