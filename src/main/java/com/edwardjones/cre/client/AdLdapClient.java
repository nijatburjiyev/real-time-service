package com.edwardjones.cre.client;

import com.edwardjones.cre.model.domain.AppUser;
import java.util.List;
import java.util.Optional;

/**
 * Interface for Active Directory/LDAP operations.
 * Allows swapping between mock and production implementations.
 */
public interface AdLdapClient {
    List<AppUser> fetchAllUsers();
    Optional<AppUser> fetchUserByUsername(String username);
}
