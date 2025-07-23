package com.edwardjones.cre.client.impl.production;

import com.edwardjones.cre.client.AdLdapClient;
import com.edwardjones.cre.model.domain.AppUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.ldap.control.PagedResultsDirContextProcessor;
import org.springframework.ldap.core.AttributesMapper;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.stereotype.Service;

import javax.naming.NamingException;
import javax.naming.directory.Attributes;
import javax.naming.directory.SearchControls;
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

    // === PAGINATION AND LIMIT CONSTANTS ===
    // Server-side limits to prevent LDAP server overload
    private static final int PAGE_SIZE = 500;                    // Results per page (well below typical 1000 limit)
    private static final int SERVER_COUNT_LIMIT = 10000;         // Maximum results LDAP server should return
    private static final int SERVER_TIME_LIMIT_SECONDS = 30;     // Maximum time LDAP server should spend on query

    // Client-side safety caps
    private static final int HARD_LIMIT_TOTAL_USERS = 50000;     // Absolute maximum users we'll process
    private static final int MAX_PAGES_PER_BASE = 100;           // Maximum pages to fetch per search base

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
        log.info("Pagination settings - Page size: {}, Server count limit: {}, Time limit: {}s, Hard limit: {}",
                PAGE_SIZE, SERVER_COUNT_LIMIT, SERVER_TIME_LIMIT_SECONDS, HARD_LIMIT_TOTAL_USERS);

        List<AppUser> allUsers = new ArrayList<>();
        int totalUsersProcessed = 0;

        for (String base : SEARCH_BASES) {
            log.info("Querying LDAP with base: {}", base);
            try {
                List<AppUser> usersInBase = fetchUsersFromBase(base);
                allUsers.addAll(usersInBase);
                totalUsersProcessed += usersInBase.size();

                log.info("Found {} users in OU: {} (Running total: {})", usersInBase.size(), base, totalUsersProcessed);

                // Client-side safety check
                if (totalUsersProcessed >= HARD_LIMIT_TOTAL_USERS) {
                    log.warn("Reached hard limit of {} users. Stopping to prevent memory issues.", HARD_LIMIT_TOTAL_USERS);
                    break;
                }
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

    /**
     * Fetches users from a single LDAP base using explicit paged results.
     * Handles pagination transparently and applies both server-side and client-side limits.
     */
    private List<AppUser> fetchUsersFromBase(String base) {
        List<AppUser> users = new ArrayList<>();
        PagedResultsDirContextProcessor processor = new PagedResultsDirContextProcessor(PAGE_SIZE);
        AttributesMapper<AppUser> mapper = new UserAttributesMapper();
        int pageCount = 0;

        do {
            pageCount++;
            log.debug("Fetching page {} from base: {} (Page size: {})", pageCount, base, PAGE_SIZE);

            try {
                // Use the correct Spring LDAP approach with explicit typed AttributesMapper
                List<AppUser> pageResults = ldapTemplate.search(
                        base,
                        "(&(objectclass=user)(|(sAMAccountName=p*)(sAMAccountName=j*)))",
                        getSearchControls(),
                        (AttributesMapper<AppUser>) mapper,
                        processor
                );

                // Filter out any null results from mapping failures
                List<AppUser> validResults = pageResults.stream()
                        .filter(Objects::nonNull)
                        .toList();

                users.addAll(validResults);
                log.debug("Page {} returned {} users. Total so far: {}", pageCount, validResults.size(), users.size());

                // Client-side safety checks
                if (pageCount >= MAX_PAGES_PER_BASE) {
                    log.warn("Reached maximum pages ({}) for base: {}. Stopping pagination for this base.",
                            MAX_PAGES_PER_BASE, base);
                    break;
                }

                if (users.size() >= HARD_LIMIT_TOTAL_USERS) {
                    log.warn("Reached hard limit ({}) for base: {}. Stopping pagination.",
                            HARD_LIMIT_TOTAL_USERS, base);
                    break;
                }

            } catch (Exception e) {
                log.error("Error during paginated search on page {} for base: {}. Error: {}",
                         pageCount, base, e.getMessage());
                break;
            }

        } while (processor.hasMore());

        log.info("Completed paginated search for base: {} - {} pages, {} total users", base, pageCount, users.size());
        return users;
    }

    /**
     * Creates SearchControls with server-side limits.
     */
    private SearchControls getSearchControls() {
        SearchControls searchControls = new SearchControls();
        searchControls.setSearchScope(SearchControls.SUBTREE_SCOPE);
        searchControls.setReturningAttributes(REQUESTED_ATTRIBUTES);
        searchControls.setCountLimit(SERVER_COUNT_LIMIT);           // Server should not return more than this
        searchControls.setTimeLimit(SERVER_TIME_LIMIT_SECONDS * 1000); // Convert to milliseconds
        return searchControls;
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
