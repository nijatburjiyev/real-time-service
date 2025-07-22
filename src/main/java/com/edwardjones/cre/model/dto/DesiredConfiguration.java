package com.edwardjones.cre.model.dto;

import java.util.Set;

/**
 * An immutable DTO representing the final, calculated state for a user
 * that should be pushed to the vendor.
 *
 * This separates calculation results from JPA entities, keeping domain objects pure.
 */
public record DesiredConfiguration(
    String username,
    String firstName,
    String lastName,
    String visibilityProfile,
    Set<String> groups,
    boolean isActive
) {}
