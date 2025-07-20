package com.edwardjones.cre.client;

import com.edwardjones.cre.model.domain.AppUser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class AdLdapClient {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Enhanced mock implementation that loads realistic AD data from JSON file
     * and converts it to AppUser entities matching actual AD structure.
     */
    public List<AppUser> fetchAllUsers() {
        log.info("MOCK - Fetching all users from LDAP...");

        try {
            // Try to load from JSON file first (for realistic testing)
            return loadUsersFromJsonFile();
        } catch (Exception e) {
            log.warn("Could not load users from JSON file, falling back to hardcoded data: {}", e.getMessage());
            return createHardcodedMockUsers();
        }
    }

    /**
     * Load users from test JSON file that matches real AD structure
     */
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

    /**
     * Convert AD JSON structure to AppUser entity
     */
    private AppUser convertAdDataToAppUser(Map<String, Object> adUser) {
        AppUser user = new AppUser();

        // Basic user information
        user.setUsername((String) adUser.get("SAMAccountName"));
        user.setEmployeeId((String) adUser.get("EmployeeID"));
        user.setFirstName((String) adUser.get("GivenName"));
        user.setLastName((String) adUser.get("Surname"));
        user.setTitle((String) adUser.get("Title"));
        user.setDistinguishedName((String) adUser.get("DistinguishedName"));
        user.setCountry((String) adUser.get("Country"));
        user.setActive((Boolean) adUser.getOrDefault("Enabled", true));

        // Parse manager from DN
        String managerDn = (String) adUser.get("Manager");
        if (managerDn != null) {
            user.setManagerUsername(extractUsernameFromDn(managerDn));
        }

        log.debug("Converted AD user: {} ({}) - {}",
                user.getUsername(), user.getEmployeeId(), user.getTitle());

        return user;
    }

    /**
     * Extract username from Distinguished Name
     * Example: "CN=p100001,OU=Managers,..." -> "p100001"
     */
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

    /**
     * Fallback hardcoded data (updated to match realistic structure)
     */
    private List<AppUser> createHardcodedMockUsers() {
        List<AppUser> mockUsers = new ArrayList<>();

        // VP - Compliance (Top Level Manager)
        AppUser vpCompliance = new AppUser();
        vpCompliance.setUsername("p100001");
        vpCompliance.setEmployeeId("0098765");
        vpCompliance.setFirstName("Katherine");
        vpCompliance.setLastName("Powell");
        vpCompliance.setTitle("Vice President - Compliance");
        vpCompliance.setDistinguishedName("CN=p100001,OU=Managers,OU=Home Office,OU=US,OU=People,DC=edj,DC=ad,DC=edwardjones,DC=com");
        vpCompliance.setCountry("US");
        vpCompliance.setManagerUsername("j999999"); // Executive level
        vpCompliance.setActive(true);
        mockUsers.add(vpCompliance);

        // Senior Compliance Analyst (HO Associate)
        AppUser complianceAnalyst = new AppUser();
        complianceAnalyst.setUsername("p200001");
        complianceAnalyst.setEmployeeId("0087654");
        complianceAnalyst.setFirstName("David");
        complianceAnalyst.setLastName("Chen");
        complianceAnalyst.setTitle("Senior Compliance Analyst");
        complianceAnalyst.setDistinguishedName("CN=p200001,OU=Associates,OU=Home Office,OU=US,OU=People,DC=edj,DC=ad,DC=edwardjones,DC=com");
        complianceAnalyst.setCountry("US");
        complianceAnalyst.setManagerUsername("p100001");
        complianceAnalyst.setActive(true);
        mockUsers.add(complianceAnalyst);

        // Branch Team Support Leader (j-number, Leader in Branch)
        AppUser branchLeader = new AppUser();
        branchLeader.setUsername("j050001");
        branchLeader.setEmployeeId("0076543");
        branchLeader.setFirstName("Maria");
        branchLeader.setLastName("Garcia");
        branchLeader.setTitle("Branch Team Support Leader");
        branchLeader.setDistinguishedName("CN=j050001,OU=Leaders,OU=Branch,OU=US,OU=People,DC=edj,DC=ad,DC=edwardjones,DC=com");
        branchLeader.setCountry("US");
        branchLeader.setManagerUsername("p100001");
        branchLeader.setActive(true);
        mockUsers.add(branchLeader);

        // Financial Advisor (Branch FA with IR Number)
        AppUser financialAdvisor = new AppUser();
        financialAdvisor.setUsername("p300001");
        financialAdvisor.setEmployeeId("0104553");
        financialAdvisor.setFirstName("John");
        financialAdvisor.setLastName("Frank");
        financialAdvisor.setTitle("Financial Advisor");
        financialAdvisor.setDistinguishedName("CN=p300001,OU=FA,OU=Branch,OU=US,OU=People,DC=edj,DC=ad,DC=edwardjones,DC=com");
        financialAdvisor.setCountry("US");
        financialAdvisor.setManagerUsername("j050001");
        financialAdvisor.setActive(true);
        mockUsers.add(financialAdvisor);

        // Branch Office Administrator
        AppUser branchAdmin = new AppUser();
        branchAdmin.setUsername("p300002");
        branchAdmin.setEmployeeId("0104554");
        branchAdmin.setFirstName("Kevin");
        branchAdmin.setLastName("Florer");
        branchAdmin.setTitle("Branch Office Administrator");
        branchAdmin.setDistinguishedName("CN=p300002,OU=BOA,OU=Branch,OU=US,OU=People,DC=edj,DC=ad,DC=edwardjones,DC=com");
        branchAdmin.setCountry("US");
        branchAdmin.setManagerUsername("j050001");
        branchAdmin.setActive(true);
        mockUsers.add(branchAdmin);

        // Canadian User (Home Office Associate)
        AppUser canadianUser = new AppUser();
        canadianUser.setUsername("p400001");
        canadianUser.setEmployeeId("0065432");
        canadianUser.setFirstName("Aisha");
        canadianUser.setLastName("Khan");
        canadianUser.setTitle("Marketing Coordinator");
        canadianUser.setDistinguishedName("CN=p400001,OU=Associates,OU=Home Office,OU=CA,OU=People,DC=edj,DC=ad,DC=edwardjones,DC=com");
        canadianUser.setCountry("CA");
        canadianUser.setManagerUsername("p100001");
        canadianUser.setActive(true);
        mockUsers.add(canadianUser);

        log.info("MOCK - Generated {} hardcoded test users", mockUsers.size());
        return mockUsers;
    }
}
