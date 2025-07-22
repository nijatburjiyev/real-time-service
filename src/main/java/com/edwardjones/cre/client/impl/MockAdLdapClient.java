package com.edwardjones.cre.client.impl;

import com.edwardjones.cre.client.AdLdapClient;
import com.edwardjones.cre.model.domain.AppUser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@Profile("test")
public class MockAdLdapClient implements AdLdapClient {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public List<AppUser> fetchAllUsers() {
        log.info("MOCK - Fetching all users from LDAP...");

        try {
            return loadUsersFromJsonFile();
        } catch (Exception e) {
            log.warn("Could not load users from JSON file, falling back to hardcoded data: {}", e.getMessage());
            return createHardcodedMockUsers();
        }
    }

    private List<AppUser> loadUsersFromJsonFile() throws IOException {
        log.info("Loading users from test-ad-users.json file");

        ClassPathResource resource = new ClassPathResource("test-data/test-ad-users.json");
        List<Map<String, Object>> adUserData = objectMapper.readValue(
            resource.getInputStream(),
            new TypeReference<List<Map<String, Object>>>() {}
        );

        List<AppUser> users = new ArrayList<>();
        for (Map<String, Object> adUser : adUserData) {
            AppUser user = convertAdDataToAppUser(adUser);
            users.add(user);
        }

        log.info("MOCK - Generated {} users from JSON file", users.size());
        return users;
    }

    private AppUser convertAdDataToAppUser(Map<String, Object> adUser) {
        AppUser user = new AppUser();
        user.setUsername((String) adUser.get("SAMAccountName"));
        user.setEmployeeId((String) adUser.get("EmployeeID"));
        user.setFirstName((String) adUser.get("GivenName"));
        user.setLastName((String) adUser.get("Surname"));
        user.setTitle((String) adUser.get("Title"));
        user.setDistinguishedName((String) adUser.get("DistinguishedName"));
        user.setCountry((String) adUser.get("Country"));
        user.setActive((Boolean) adUser.getOrDefault("Enabled", true));

        String managerDn = (String) adUser.get("Manager");
        if (managerDn != null) {
            user.setManagerUsername(extractUsernameFromDn(managerDn));
        }

        return user;
    }

    private String extractUsernameFromDn(String dn) {
        if (dn == null || !dn.startsWith("CN=")) {
            return null;
        }
        int commaIndex = dn.indexOf(',');
        if (commaIndex > 3) {
            return dn.substring(3, commaIndex);
        }
        return null;
    }

    private List<AppUser> createHardcodedMockUsers() {
        // Fallback hardcoded users for when JSON loading fails
        List<AppUser> users = new ArrayList<>();

        AppUser user1 = new AppUser();
        user1.setUsername("p100001");
        user1.setFirstName("Katherine");
        user1.setLastName("Powell");
        user1.setTitle("Vice President - Compliance");
        user1.setCountry("US");
        user1.setActive(true);
        users.add(user1);

        return users;
    }
}
