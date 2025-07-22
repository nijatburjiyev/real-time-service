package com.edwardjones.cre.client.impl.production;

import com.edwardjones.cre.client.AdLdapClient;
import com.edwardjones.cre.model.domain.AppUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.ldap.core.AttributesMapper;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.query.LdapQueryBuilder;
import org.springframework.stereotype.Service;

import javax.naming.NamingException;
import javax.naming.directory.Attributes;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@Profile("!test")
@RequiredArgsConstructor
public class ProductionAdLdapClient implements AdLdapClient {

    private static final Pattern PJ_NUMBER_PATTERN = Pattern.compile("CN=((p|j)\\d{5,6}),", Pattern.CASE_INSENSITIVE);
    private static final String[] REQUESTED_ATTRIBUTES = {
            "Name", "GivenName", "Surname", "SAMAccountName", "DistinguishedName", "ej-title", "Title",
            "EmailAddress", "EmployeeID", "Manager", "Department", "State", "Country", "Enabled"
    };

    private final LdapTemplate ldapTemplate;

    @Value("${app.client.ldap.search-bases}")
    private List<String> searchBases;

    @Override
    public List<AppUser> fetchAllUsers() {
        log.info("Fetching all users from production AD/LDAP across {} search bases...", searchBases.size());

        return searchBases.stream()
                .flatMap(base -> {
                    log.debug("Querying LDAP with base: {}", base);
                    return ldapTemplate.search(
                            LdapQueryBuilder.query().base(base)
                                    .attributes(REQUESTED_ATTRIBUTES)
                                    .filter("(&(objectclass=user)(|(Name=p*)(Name=j*)))"),
                            new UserAttributesMapper()
                    ).stream();
                })
                .distinct() // In case a user somehow exists in multiple OUs
                .collect(Collectors.toList());
    }

    private static class UserAttributesMapper implements AttributesMapper<AppUser> {
        @Override
        public AppUser mapFromAttributes(Attributes attrs) throws NamingException {
            AppUser user = new AppUser();
            user.setUsername(getAttribute(attrs, "SAMAccountName"));
            user.setEmployeeId(getAttribute(attrs, "EmployeeID"));
            user.setFirstName(getAttribute(attrs, "GivenName"));
            user.setLastName(getAttribute(attrs, "Surname"));
            user.setTitle(getAttribute(attrs, "Title"));
            user.setDistinguishedName(getAttribute(attrs, "DistinguishedName"));
            user.setCountry(getAttribute(attrs, "Country"));
            user.setActive("TRUE".equalsIgnoreCase(getAttribute(attrs, "Enabled")));

            String managerDn = getAttribute(attrs, "Manager");
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
