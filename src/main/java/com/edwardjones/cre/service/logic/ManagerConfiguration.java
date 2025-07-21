package com.edwardjones.cre.service.logic;

import lombok.Data;
import java.util.Map;
import java.util.Set;

/**
 * Represents pre-calculated manager configuration data.
 * This is the Java equivalent of PowerShell's $managers hashtable entries.
 */
@Data
public class ManagerConfiguration {
    private String username;
    private String department;
    private Map<String, Integer> countries; // Country -> count of reports
    private Map<String, String> groups; // Country -> group name for reports
    private String visibilityProfileName; // VP for their reports
    private String visibilityProfileNameSelf; // VP for the manager themselves
    private boolean isHomeOffice;
    private boolean isBranch;
    private Set<String> teamIds; // CRT team IDs this manager owns
}
