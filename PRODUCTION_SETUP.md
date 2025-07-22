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

### Environment Variables

Set these environment variables for production deployment:

```bash
# LDAP Configuration
export LDAP_URL="ldap://ad.edwardjones.com:389"
export LDAP_BASE="DC=edj,DC=ad,DC=edwardjones,DC=com"
export LDAP_USERNAME="CN=service-account,OU=Service Accounts,DC=edj,DC=ad,DC=edwardjones,DC=com"
export LDAP_PASSWORD="your-secure-ldap-password"

# CRBT API Configuration
export CRBT_API_BASE_URL="https://crbt-api.edwardjones.com/api/v1"
export CRBT_EJAUTH_TOKEN="your-ejauth-token"
export CRBT_EJPKY_TOKEN="your-ejpky-token"

# Vendor API Configuration
export VENDOR_API_BASE_URL="https://vendor-api.example.com/api/v1"
export VENDOR_API_KEY="your-vendor-api-key"
```

### Application Profiles

The production clients are activated automatically when not running in test mode:

```bash
# For production deployment
java -jar compliance-sync-service.jar

# For development (uses mocks)
java -jar compliance-sync-service.jar --spring.profiles.active=test
```

## Security Considerations

### 1. Credential Management
- Store sensitive credentials in secure vaults (Azure Key Vault, HashiCorp Vault)
- Use environment variables for configuration
- Never commit credentials to source control

### 2. Network Security
- Ensure LDAP connections use secure protocols
- Configure firewall rules for API endpoints
- Use VPN or private networks for internal communications

### 3. Authentication
- LDAP: Uses service account with minimal required permissions
- CRBT API: Uses session-based cookie authentication
- Vendor API: Uses API key authentication with proper rotation

## Deployment Steps

### 1. Prerequisites
```bash
# Install required dependencies
./gradlew build

# Verify configuration
./gradlew test
```

### 2. Production Deployment
```bash
# Set environment variables (see above)
export SPRING_PROFILES_ACTIVE=production

# Start the service
java -jar build/libs/compliance-sync-service-1.0.0-SNAPSHOT.jar
```

### 3. Health Checks
Monitor these endpoints for service health:
- `/actuator/health` - Overall application health
- Application logs for client connection status
- Database connectivity for state management

## Troubleshooting

### Common Issues

#### LDAP Connection Issues
```
ERROR: Failed to connect to LDAP server
```
**Solutions:**
- Verify LDAP URL and port accessibility
- Check service account credentials
- Ensure proper network connectivity

#### CRBT API Authentication Failures
```
ERROR: 401 Unauthorized from CRBT API
```
**Solutions:**
- Verify EJAUTH and EJPKY tokens are current
- Check token expiration dates
- Regenerate authentication cookies if needed

#### Vendor API Issues
```
ERROR: Failed to update user in vendor system
```
**Solutions:**
- Verify API key validity
- Check vendor API endpoint availability
- Review rate limiting policies

### Monitoring

Set up monitoring for:
- LDAP connection pool health
- API response times and error rates
- Database performance metrics
- Kafka consumer lag

## Performance Tuning

### LDAP Optimization
- Configure connection pooling
- Optimize search filters and attributes
- Use pagination for large result sets

### API Client Tuning
- Configure appropriate timeouts
- Implement retry mechanisms
- Use connection pooling for HTTP clients

## Backup and Recovery

### State Database
- Regular backups of H2 database files
- Consider migration to PostgreSQL for production scale

### Configuration Backup
- Version control all configuration files
- Document environment-specific settings
- Maintain rollback procedures

## Support

For production issues:
1. Check application logs
2. Verify external system connectivity
3. Review configuration settings
4. Contact infrastructure team for network issues
