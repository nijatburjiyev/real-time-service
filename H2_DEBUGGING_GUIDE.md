# H2 File-Based Database Debugging Guide

## Overview
Your test database is now configured to use a file-based H2 database instead of in-memory, allowing manual inspection during debugging.

## Database Configuration
- **Location**: `./build/data/test_compliance_state_db.mv.db`
- **Connection URL**: `jdbc:h2:file:./build/data/test_compliance_state_db;AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE`
- **Username**: `sa`
- **Password**: (empty)

## Debugging Workflows

### Workflow A: Debugger Breakpoint Method (Recommended)
This maintains test isolation while allowing live database inspection.

1. **Run Single Test in Debug Mode**
   - Right-click test method in IntelliJ → "Debug 'testMethod()'"

2. **Set Breakpoint**
   - Place breakpoint on the last line of your test method:
   ```java
   @Test
   void testYourMethod() {
       // ... test logic ...
       
       // Verify results
       verify(mockVendorApiClient, times(5)).updateUser(userCaptor.capture());
       
       log.info("✅ Test completed"); // <-- SET BREAKPOINT HERE
   }
   ```

3. **Inspect Live Database**
   - When debugger pauses, connect to database using IntelliJ Database tool
   - Browse tables: `APP_USER`, `CRBT_TEAM`, `USER_TEAM_MEMBERSHIP`
   - Run custom queries to inspect state

4. **Resume Execution**
   - Resume debugger to let test complete
   - `@DirtiesContext` will clean up for next test

### Workflow B: Manual Inspection Method (Temporary)
For when you need persistent data after test completion. **Use temporarily only!**

1. **Temporarily Modify Configuration**
   ```yaml
   # In application-test.yml
   jpa:
     hibernate:
       # ddl-auto: create-drop  # Comment out
       ddl-auto: update        # Use update to preserve data
   ```

2. **Temporarily Disable Context Cleaning**
   ```java
   // In UnifiedComplianceIntegrationTest.java
   @SpringBootTest
   @ActiveProfiles("test")
   // @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD) // Comment out
   public class UnifiedComplianceIntegrationTest {
   ```

3. **Run Test and Inspect**
   - Run test normally
   - Database file persists after completion
   - Connect with IntelliJ Database tool

4. **CRITICAL: Revert Changes**
   - Uncomment `ddl-auto: create-drop`
   - Uncomment `@DirtiesContext`
   - Run `./gradlew clean` to remove persistent data

## IntelliJ Database Tool Setup

1. **Open Database Tool Window**
   - View → Tool Windows → Database

2. **Add New Data Source**
   - Click `+` → Data Source → H2

3. **Configure Connection**
   - **Name**: "Compliance Test DB"
   - **URL**: `jdbc:h2:file:./build/data/test_compliance_state_db;AUTO_SERVER=TRUE`
   - **User**: `sa`
   - **Password**: (leave empty)

4. **Test Connection**
   - Click "Test Connection" (only works when test is running or file exists)
   - Click "Apply" to save

## Key Benefits

- **Manual Inspection**: See exact database state after test execution
- **Complex Debugging**: Understand data relationships and transformations
- **Regression Analysis**: Compare database states across test runs
- **Query Testing**: Run ad-hoc queries against test data

## Important Notes

- Database files are stored in `build/` directory (cleaned by `./gradlew clean`)
- `AUTO_SERVER=TRUE` allows simultaneous connections from app and IDE
- Current `@DirtiesContext` ensures test isolation
- H2 Console available at `/h2-console` during test execution

## Troubleshooting

### Database File Not Found
- Ensure test runs successfully first
- Check `build/data/` directory exists
- Verify no ApplicationContext startup failures

### Connection Refused
- Ensure test is still running (for live inspection)
- Or ensure test completed successfully (for post-run inspection)
- Check `AUTO_SERVER=TRUE` parameter in URL

### Data Not Persisting
- Verify `ddl-auto: create-drop` setting
- Check if `@DirtiesContext` is cleaning context
- Ensure you're using Workflow B if you need persistence
