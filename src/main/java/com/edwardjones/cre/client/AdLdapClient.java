package com.edwardjones.cre.client;

import com.edwardjones.cre.model.domain.AppUser;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;

@Service
public class AdLdapClient {
    public List<AppUser> fetchAllUsers() {
        // TODO: Implement LDAP connection and query logic here
        // This should return a list of AppUser objects
        System.out.println("MOCK - Fetching all users from LDAP...");
        return new ArrayList<>();
    }
}
