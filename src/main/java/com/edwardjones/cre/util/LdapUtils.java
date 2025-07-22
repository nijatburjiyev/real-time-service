package com.edwardjones.cre.util;

import lombok.extern.slf4j.Slf4j;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class for parsing Active Directory Distinguished Names (DNs).
 * Centralizes DN parsing logic to ensure consistency across the application.
 */
@Slf4j
public final class LdapUtils {

    private static final Pattern PJ_NUMBER_PATTERN = Pattern.compile("CN=((p|j)\\d{5,6}),", Pattern.CASE_INSENSITIVE);

    private LdapUtils() {
        // Utility class - prevent instantiation
    }

    /**
     * Parse PJ Number (username) from Distinguished Name.
     *
     * @param dn the distinguished name string
     * @return the extracted PJ number/username, or null if not found
     */
    public static String parseUsernameFromDn(String dn) {
        if (dn == null || dn.isEmpty()) {
            return null;
        }

        Matcher matcher = PJ_NUMBER_PATTERN.matcher(dn);
        if (matcher.find()) {
            String username = matcher.group(1);
            log.debug("Parsed username '{}' from DN: {}", username, dn);
            return username;
        }

        log.warn("⚠️ Could not parse PJ Number from DN: {}", dn);
        return null;
    }

    /**
     * Check if a distinguished name indicates a Home Office location.
     *
     * @param dn the distinguished name string
     * @return true if the DN contains Home Office organizational unit
     */
    public static boolean isHomeOfficeLocation(String dn) {
        return dn != null && dn.contains("OU=Home Office");
    }

    /**
     * Check if a distinguished name indicates a Branch location.
     *
     * @param dn the distinguished name string
     * @return true if the DN contains Branch organizational unit
     */
    public static boolean isBranchLocation(String dn) {
        return dn != null && dn.contains("OU=Branch");
    }
}
