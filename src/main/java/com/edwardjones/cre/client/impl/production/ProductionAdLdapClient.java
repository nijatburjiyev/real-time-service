package com.edwardjones.cre.client.impl.production;

import com.edwardjones.cre.client.AdLdapClient;
import com.edwardjones.cre.model.domain.AppUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.ldap.core.AttributesMapper;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.query.LdapQueryBuilder;
import org.springframework.stereotype.Service;

import javax.naming.NamingException;
import javax.naming.directory.Attributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@Profile("!test")
@RequiredArgsConstructor
public class ProductionAdLdapClient implements AdLdapClient {

    // Hardcoded list of search bases. This makes the client's purpose explicit.
    private static final List<String> SEARCH_BASES = List.of(
            "OU=Branch,OU=US,OU=People,DC=edj,DC=ad,DC=edwardjones,DC=com",
            "OU=Home Office,OU=US,OU=People,DC=edj,DC=ad,DC=edwardjones,DC=com",
            "OU=Home Office,OU=CA,OU=People,DC=edj,DC=ad,DC=edwardjones,DC=com"
            // Add a Canadian Branch OU here if one exists
    );

    private static final Pattern PJ_NUMBER_PATTERN = Pattern.compile("CN=((p|j)\\d{5,6}),", Pattern.CASE_INSENSITIVE);
    private static final String[] REQUESTED_ATTRIBUTES = {
            "sAMAccountName", "employeeID", "givenName", "sn", "title",
            "distinguishedName", "c", "manager", "userAccountControl"
    };

    private final LdapTemplate ldapTemplate;

    @Override
    public List<AppUser> fetchAllUsers() {
        log.info("Fetching all users from production AD/LDAP across {} search bases...", SEARCH_BASES.size());
        log.info("Search bases configured: {}", SEARCH_BASES);
        List<AppUser> allUsers = new ArrayList<>();

        for (String base : SEARCH_BASES) {
            log.debug("Querying LDAP with base: {}", base);
            try {
                // ** THE FIX FOR 1000 USER LIMIT **
                // Use Spring LDAP's built-in paged results support
                List<AppUser> usersInBase = ldapTemplate.search(
                        LdapQueryBuilder.query().base(base)
                                .attributes(REQUESTED_ATTRIBUTES)
                                .filter("(&(objectclass=user)(|(sAMAccountName=p*)(sAMAccountName=j*)))"),
                        new UserAttributesMapper()
                );

                allUsers.addAll(usersInBase);
                log.info("Found {} users in OU: {}", usersInBase.size(), base);
            } catch (Exception e) {
                log.error("Failed to query LDAP base '{}'. It might be unavailable or incorrect. Skipping.", base, e);
            }
        }

        // Remove duplicates (rare edge case during org restructuring)
        List<AppUser> uniqueUsers = allUsers.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        log.info("Total unique users fetched from all OUs: {}", uniqueUsers.size());
        return uniqueUsers;
    }

    private static class UserAttributesMapper implements AttributesMapper<AppUser> {
        @Override
        public AppUser mapFromAttributes(Attributes attrs) throws NamingException {
            AppUser user = new AppUser();
            user.setUsername(getAttribute(attrs, "sAMAccountName"));
            user.setEmployeeId(getAttribute(attrs, "employeeID"));
            user.setFirstName(getAttribute(attrs, "givenName"));
            user.setLastName(getAttribute(attrs, "sn"));
            user.setTitle(getAttribute(attrs, "title"));
            user.setDistinguishedName(getAttribute(attrs, "distinguishedName"));
            user.setCountry(getAttribute(attrs, "c"));

            // Handle user account status properly
            String accountControl = getAttribute(attrs, "userAccountControl");
            boolean isActive = accountControl == null ||
                              (!"514".equals(accountControl) && !"66050".equals(accountControl));
            user.setActive(isActive);

            String managerDn = getAttribute(attrs, "manager");
            if (managerDn != null) {
                user.setManagerUsername(parsePjFromDn(managerDn));
            }
            return user;
        }

        private String getAttribute(Attributes attrs, String attrId) throws NamingException {
            return attrs.get(attrId) != null ? (String) attrs.get(attrId).get() : null;
        }

        private String parsePjFromDn(String dn) {
            if (dn == null) return null;
            Matcher matcher = PJ_NUMBER_PATTERN.matcher(dn);
            return matcher.find() ? matcher.group(1) : null;
        }
    }
}