# Production Client Configuration Guide

This document explains how to configure and deploy the real-time compliance service with production client implementations.

## Overview

The service supports two deployment modes:
- **Test Mode**: Uses mock implementations for development and testing
- **Production Mode**: Uses real LDAP, CRBT API, and Vendor API clients

## Production Client Implementations

### 1. Active Directory LDAP Client
**File**: `ProductionAdLdapClient.java`
- Connects to Edwards Jones Active Directory via LDAP
- Fetches user data from multiple organizational units
- Parses distinguished names to extract manager relationships
- Handles authentication with service account credentials

### 2. CRBT API Client
**File**: `ProductionCrbtApiClient.java`
- Connects to the CRBT (Customer Relationship Business Teams) API
- Uses Edwards Jones authentication cookies (EJAUTH, EJPKY)
- Fetches team and member data for compliance calculations

### 3. Vendor API Client
**File**: `ProductionVendorApiClient.java`
- Connects to the external vendor's compliance management system
- Uses API key authentication
- Supports CRUD operations for users, groups, and visibility profiles
- Includes fallback logic (404 → create user)

## Configuration

Based on your requirements, here's how to configure and run the production mode without profiles, with Kafka disabled, focusing on bootstrap and H2 database verification:

## 1. Update Main application.properties

Replace your `src/main/resources/application.properties` with:

```properties
# Server Configuration
server.port=8080
spring.application.name=compliance-sync-service

# H2 File-based Database Configuration
spring.datasource.url=jdbc:h2:file:./build/data/compliance_production_db;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;AUTO_SERVER=TRUE
spring.datasource.driverClassName=org.h2.Driver
spring.datasource.username=sa
spring.datasource.password=
spring.jpa.database-platform=org.hibernate.dialect.H2Dialect
spring.jpa.hibernate.ddl-auto=update
spring.jpa.show-sql=true
spring.h2.console.enabled=true
spring.h2.console.path=/h2-console
spring.h2.console.settings.web-allow-others=true

# LDAP Configuration
ldap.url=${LDAP_URL:ldap://ad.edwardjones.com:389}
ldap.base=${LDAP_BASE:DC=edj,DC=ad,DC=edwardjones,DC=com}
ldap.username=${LDAP_USERNAME:CN=service-account,OU=Service Accounts,DC=edj,DC=ad,DC=edwardjones,DC=com}
ldap.password=${LDAP_PASSWORD:default-password}
ldap.connectionTimeout=5000
ldap.readTimeout=10000
ldap.poolSize=10

# CRBT API Configuration
crbt.api.baseUrl=${CRBT_API_BASE_URL:https://crbt-api.edwardjones.com/api/v1}
crbt.api.ejAuthToken=${CRBT_EJAUTH_TOKEN:default-token}
crbt.api.ejPkyToken=${CRBT_EJPKY_TOKEN:default-token}
crbt.api.connectionTimeout=10000
crbt.api.readTimeout=30000
crbt.api.maxRetries=3
crbt.api.retryDelay=1000

# Vendor API Configuration
vendor.api.baseUrl=${VENDOR_API_BASE_URL:https://vendor-api.example.com/api/v1}
vendor.api.key=${VENDOR_API_KEY:default-api-key}
vendor.api.connectionTimeout=10000
vendor.api.readTimeout=30000
vendor.api.maxRetries=3
vendor.api.retryDelay=1000

# DISABLE Kafka
spring.kafka.enabled=false
spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration

# Actuator Configuration
management.endpoints.web.exposure.include=health,info,metrics,beans
management.endpoint.health.show-details=always

# Logging Configuration
logging.level.root=INFO
logging.level.com.edwardjones.cre=DEBUG
logging.level.org.springframework.boot=DEBUG
logging.level.org.hibernate.SQL=DEBUG
logging.level.org.hibernate.type.descriptor.sql.BasicBinder=TRACE

# Bootstrap Configuration
app.bootstrap.enabled=true
app.bootstrap.batchSize=50
app.bootstrap.logProgress=true

# Disable scheduling for testing
app.reconciliation.schedule.enabled=false
```

## 2. Disable Kafka in Your Code

Update your Kafka listener class to check if Kafka is enabled:

```java
@Component
@ConditionalOnProperty(
    value = "spring.kafka.enabled",
    havingValue = "true",
    matchIfMissing = false
)
public class ComplianceEventListener {
    // Your existing Kafka listener code
}
```

## 3. Create Bootstrap Verification Endpoint

Add this controller to verify bootstrap data:

```java
@RestController
@RequestMapping("/api/bootstrap")
public class BootstrapVerificationController {

    @Autowired
    private AdUserRepository adUserRepository;

    @Autowired
    private CrbtTeamRepository crbtTeamRepository;

    @Autowired
    private TeamMemberRepository teamMemberRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @GetMapping("/status")
    public Map<String, Object> getBootstrapStatus() {
        Map<String, Object> status = new HashMap<>();
        
        // Database counts
        status.put("adUserCount", adUserRepository.count());
        status.put("crbtTeamCount", crbtTeamRepository.count());
        status.put("teamMemberCount", teamMemberRepository.count());
        
        // Database file info
        String dbPath = jdbcTemplate.queryForObject(
            "SELECT SETTING_VALUE FROM INFORMATION_SCHEMA.SETTINGS WHERE SETTING_NAME = 'info.FILE_WRITE'",
            String.class
        );
        status.put("databasePath", dbPath);
        
        // Sample data
        if (adUserRepository.count() > 0) {
            status.put("sampleAdUsers", adUserRepository.findAll()
                .stream()
                .limit(5)
                .map(u -> u.getUsername() + " - " + u.getEmail())
                .collect(Collectors.toList()));
        }
        
        if (crbtTeamRepository.count() > 0) {
            status.put("sampleTeams", crbtTeamRepository.findAll()
                .stream()
                .limit(5)
                .map(t -> t.getTeamName() + " (ID: " + t.getCrbtId() + ")")
                .collect(Collectors.toList()));
        }
        
        return status;
    }

    @GetMapping("/database/tables")
    public List<Map<String, Object>> getDatabaseTables() {
        return jdbcTemplate.queryForList(
            "SELECT TABLE_NAME, ROW_COUNT_ESTIMATE FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC'"
        );
    }
}
```

## 4. Configure IntelliJ Run Configuration

### Step 1: Create Run Configuration
1. Go to **Run** → **Edit Configurations**
2. Click **+** → **Spring Boot**
3. Name: `Production Bootstrap Test`

### Step 2: Configuration Settings
- **Main class**: `com.edwardjones.cre.ComplianceSyncApplication`
- **Working directory**: `$MODULE_WORKING_DIR$`

### Step 3: Environment Variables
```
LDAP_URL=ldap://ad.edwardjones.com:389
LDAP_BASE=DC=edj,DC=ad,DC=edwardjones,DC=com
LDAP_USERNAME=CN=service-account,OU=Service Accounts,DC=edj,DC=ad,DC=edwardjones,DC=com
LDAP_PASSWORD=your-ldap-password
CRBT_API_BASE_URL=https://crbt-api.edwardjones.com/api/v1
CRBT_EJAUTH_TOKEN=your-ejauth-token
CRBT_EJPKY_TOKEN=your-ejpky-token
VENDOR_API_BASE_URL=https://vendor-api.example.com/api/v1
VENDOR_API_KEY=your-vendor-api-key
```

### Step 4: VM Options (for better debugging)
```
-Dspring.jpa.show-sql=true
-Dspring.jpa.properties.hibernate.format_sql=true
```

## 5. Update Bootstrap Service for Progress Tracking

Enhance your bootstrap service to log progress:

```java
@Service
@Slf4j
public class BootstrapService {

    @PostConstruct
    public void initializeData() {
        if (!bootstrapEnabled) {
            log.info("Bootstrap is disabled");
            return;
        }

        log.info("=== STARTING BOOTSTRAP PROCESS ===");
        
        try {
            // Bootstrap AD Users
            log.info("Fetching AD users...");
            List<AdUser> adUsers = adLdapClient.fetchAllUsers();
            log.info("Fetched {} AD users from LDAP", adUsers.size());
            
            int savedUsers = 0;
            for (AdUser user : adUsers) {
                adUserRepository.save(user);
                savedUsers++;
                if (savedUsers % 100 == 0) {
                    log.info("Saved {} AD users to database", savedUsers);
                }
            }
            log.info("✓ Completed AD user bootstrap: {} users saved", savedUsers);

            // Bootstrap CRBT Teams
            log.info("Fetching CRBT teams...");
            List<CrbtApiClient.CrbtApiTeamResponse> teams = crbtApiClient.fetchAllTeamsWithMembers();
            log.info("Fetched {} teams from CRBT API", teams.size());
            
            int savedTeams = 0;
            int savedMembers = 0;
            for (var teamResponse : teams) {
                // Save team
                CrbtTeam team = convertToTeam(teamResponse);
                crbtTeamRepository.save(team);
                savedTeams++;
                
                // Save members
                for (var memberResponse : teamResponse.memberList) {
                    TeamMember member = convertToMember(memberResponse, team);
                    teamMemberRepository.save(member);
                    savedMembers++;
                }
                
                if (savedTeams % 50 == 0) {
                    log.info("Progress: {} teams and {} members saved", savedTeams, savedMembers);
                }
            }
            log.info("✓ Completed CRBT bootstrap: {} teams and {} members saved", savedTeams, savedMembers);

            log.info("=== BOOTSTRAP COMPLETED SUCCESSFULLY ===");
            
        } catch (Exception e) {
            log.error("Bootstrap failed: ", e);
            throw new RuntimeException("Bootstrap initialization failed", e);
        }
    }
}
```

## 6. Run and Verify

### Step 1: Start the Application
Click **Run** in IntelliJ. Watch the console for:
```
=== STARTING BOOTSTRAP PROCESS ===
Fetching AD users...
Fetched 1000 AD users from LDAP
Saved 100 AD users to database
...
✓ Completed AD user bootstrap: 1000 users saved
```

### Step 2: Verify Database via H2 Console
1. Open browser: `http://localhost:8080/h2-console`
2. Use these settings:
    - **Driver Class**: `org.h2.Driver`
    - **JDBC URL**: `jdbc:h2:file:./build/data/compliance_production_db`
    - **Username**: `sa`
    - **Password**: (leave empty)

3. Run queries to verify data:
```sql
-- Check record counts
SELECT 'AD_USER' as TABLE_NAME, COUNT(*) as COUNT FROM AD_USER
UNION ALL
SELECT 'CRBT_TEAM', COUNT(*) FROM CRBT_TEAM
UNION ALL
SELECT 'TEAM_MEMBER', COUNT(*) FROM TEAM_MEMBER;

-- Sample AD users
SELECT * FROM AD_USER LIMIT 10;

-- Sample teams
SELECT * FROM CRBT_TEAM LIMIT 10;
```

### Step 3: Use Verification Endpoints
```bash
# Check bootstrap status
curl http://localhost:8080/api/bootstrap/status

# Check database tables
curl http://localhost:8080/api/bootstrap/database/tables
```

### Step 4: Verify Database File
Check that the H2 database file was created:
```bash
ls -la ./build/data/
# You should see: compliance_production_db.mv.db
```

## 7. Troubleshooting

### If Bootstrap Doesn't Run
1. Check logs for "STARTING BOOTSTRAP PROCESS"
2. Verify `app.bootstrap.enabled=true` in properties
3. Ensure production clients are being loaded (not mock clients)

### If Database is Empty
1. Check for exceptions in logs
2. Verify LDAP/API credentials are correct
3. Ensure you're on the correct network/VPN

### To Test Without Real Connections
If you don't have access to production systems, create a test wrapper:

```java
@Component
@Primary  // This overrides production clients
public class BootstrapTestClient implements AdLdapClient, CrbtApiClient {
    
    @Override
    public List<AdUser> fetchAllUsers() {
        // Return test data
        return IntStream.range(1, 101)
            .mapToObj(i -> {
                AdUser user = new AdUser();
                user.setUsername("user" + i);
                user.setEmail("user" + i + "@example.com");
                user.setEmployeeId("EMP" + i);
                return user;
            })
            .collect(Collectors.toList());
    }
    
    // Implement other methods similarly
}
```

## 8. Monitor Database Growth

Add this endpoint to track database size:

```java
@GetMapping("/database/stats")
public Map<String, Object> getDatabaseStats() {
    Map<String, Object> stats = new HashMap<>();
    
    // File size
    File dbFile = new File("./build/data/compliance_production_db.mv.db");
    if (dbFile.exists()) {
        stats.put("fileSizeMB", dbFile.length() / (1024.0 * 1024.0));
        stats.put("lastModified", new Date(dbFile.lastModified()));
    }
    
    // Table sizes
    List<Map<String, Object>> tableSizes = jdbcTemplate.queryForList(
        "SELECT TABLE_NAME, STORAGE_SIZE FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC'"
    );
    stats.put("tables", tableSizes);
    
    return stats;
}
```

This setup will:
1. Run without profiles (using default application.properties)
2. Disable Kafka completely
3. Focus on bootstrap process
4. Save data to H2 file-based database
5. Provide multiple ways to verify the data was saved correctly