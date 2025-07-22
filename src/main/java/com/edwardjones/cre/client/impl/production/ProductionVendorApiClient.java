package com.edwardjones.cre.client.impl.production;

import com.edwardjones.cre.client.VendorApiClient;
import com.edwardjones.cre.model.dto.DesiredConfiguration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.List;

@Slf4j
@Service
@Profile("!test")
public class ProductionVendorApiClient implements VendorApiClient {

    private final RestTemplate restTemplate;

    public ProductionVendorApiClient(@Qualifier("vendorRestTemplate") RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    public List<VendorUser> getAllUsers() {
        log.info("Fetching all users from vendor API...");
        try {
            ResponseEntity<List<VendorUser>> response = restTemplate.exchange(
                    "/users", HttpMethod.GET, null, new ParameterizedTypeReference<>() {});
            log.info("Successfully retrieved {} users from vendor API.", response.getBody() != null ? response.getBody().size() : 0);
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to fetch users from vendor API", e);
            return Collections.emptyList();
        }
    }

    @Override
    public List<VendorGroup> getAllGroups() {
        log.info("Fetching all groups from vendor API...");
        try {
            ResponseEntity<List<VendorGroup>> response = restTemplate.exchange(
                    "/groups", HttpMethod.GET, null, new ParameterizedTypeReference<>() {});
            log.info("Successfully retrieved {} groups from vendor API.", response.getBody() != null ? response.getBody().size() : 0);
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to fetch groups from vendor API", e);
            return Collections.emptyList();
        }
    }

    @Override
    public List<VendorVisibilityProfile> getAllVisibilityProfiles() {
        log.info("Fetching all visibility profiles from vendor API...");
        try {
            ResponseEntity<List<VendorVisibilityProfile>> response = restTemplate.exchange(
                    "/visibility-profiles", HttpMethod.GET, null, new ParameterizedTypeReference<>() {});
            log.info("Successfully retrieved {} visibility profiles from vendor API.", response.getBody() != null ? response.getBody().size() : 0);
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to fetch visibility profiles from vendor API", e);
            return Collections.emptyList();
        }
    }

    @Override
    public void updateUser(DesiredConfiguration config) {
        String url = "/users/" + config.username();
        log.info("Sending UPDATE to vendor for user {}: {}", config.username(), url);
        try {
            restTemplate.put(url, config);
            log.info("Successfully updated user {} in vendor system.", config.username());
        } catch (HttpClientErrorException e) {
            log.error("Client error updating user {}: {} - {}", config.username(), e.getStatusCode(), e.getResponseBodyAsString());
            // If user not found (404), maybe we should create them instead?
            if (e.getStatusCode().value() == 404) {
                log.warn("User {} not found on update (404), attempting to create instead.", config.username());
                createUser(config);
            }
        } catch (Exception e) {
            log.error("Failed to update user {} in vendor system", config.username(), e);
        }
    }

    @Override
    public void createUser(DesiredConfiguration config) {
        String url = "/users";
        log.info("Sending CREATE to vendor for user {}: {}", config.username(), url);
        try {
            restTemplate.postForEntity(url, config, Void.class);
            log.info("Successfully created user {} in vendor system.", config.username());
        } catch (Exception e) {
            log.error("Failed to create user {} in vendor system", config.username(), e);
        }
    }
}
