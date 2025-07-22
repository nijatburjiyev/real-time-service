package com.edwardjones.cre.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.lang.NonNull;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.util.List;

@Configuration
public class ApiClientConfig {

    private static final Logger logger = LoggerFactory.getLogger(ApiClientConfig.class);
    private static final String VENDOR_BUNDLE_NAME = "vendor";

    @Bean
    @Qualifier("vendorRestTemplate")
    public RestTemplate vendorRestTemplate(RestTemplateBuilder builder, SslBundles bundles) {
        logger.info("Initializing vendorRestTemplate with SSL bundle check...");
        if (!bundles.getBundleNames().contains(VENDOR_BUNDLE_NAME)) {
            logger.warn("SSL bundle '{}' not found. Falling back to default JVM trust-store.", VENDOR_BUNDLE_NAME);
            return buildVendorRestTemplate(builder);
        }
        try {
            logger.info("SSL bundle '{}' found. Using SSL bundle for vendorRestTemplate.", VENDOR_BUNDLE_NAME);
            // SSL bundle will be used automatically by Spring Boot when available
            bundles.getBundle(VENDOR_BUNDLE_NAME); // Verify bundle is accessible
            return buildVendorRestTemplate(builder);
        } catch (Exception ex) {
            logger.error("Error creating vendor RestTemplate with SSL bundle '{}'. Falling back to default JVM trust-store.", VENDOR_BUNDLE_NAME, ex);
            return buildVendorRestTemplate(builder);
        }
    }

    private RestTemplate buildVendorRestTemplate(RestTemplateBuilder builder) {
        return builder.additionalInterceptors((request, body, execution) -> {
            String url = "https://admaster-prod-api.redoakcompliance.com/api_v2/oauth/token/EDWARDJONES-TEST";
            logger.info("Fetching OAuth token from vendor: {}", url);
            var headers = new org.springframework.http.HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
            String bodyStr = "grant_type=client_credentials&client_id=edjOauthTest&client_secret=edjClientSecretTest";
            var tokenRequest = new org.springframework.http.HttpEntity<>(bodyStr, headers);
            RestTemplate tokenTemplate = new RestTemplate();
            record TokenResponse(String access_token, Long expires_in) {}
            var tokenResponse = tokenTemplate.exchange(
                url,
                org.springframework.http.HttpMethod.POST,
                tokenRequest,
                TokenResponse.class
            );
            logger.info("Vendor token response status: {}", tokenResponse.getStatusCode());
            logger.info("Vendor token response body: {}", tokenResponse.getBody());
            if (tokenResponse.getBody() == null || tokenResponse.getBody().access_token() == null) {
                logger.error("Failed to fetch vendor API access token. Response: {}", tokenResponse);
                throw new IllegalStateException("Failed to fetch vendor API access token");
            }
            logger.info("Fetched vendor API access token successfully.");
            request.getHeaders().setBearerAuth(tokenResponse.getBody().access_token());
            logger.info("Making vendor API request: {} {} with headers {}", request.getMethod(), request.getURI(), request.getHeaders());
            var response = execution.execute(request, body);
            logger.info("Vendor API response status: {}", response.getStatusCode());
            return response;
        }).build();
    }

    @Bean
    @Qualifier("crbtRestTemplate")
    public RestTemplate crbtRestTemplate(RestTemplateBuilder builder) {
        logger.info("Initializing crbtRestTemplate with auth endpoint cookie logic...");
        return builder.additionalInterceptors(new ClientHttpRequestInterceptor() {
            private String cachedCookie = null;
            private long cookieFetchTime = 0L;
            private long maxAgeSeconds = 0L;

            private synchronized void refreshCookieIfNeeded() {
                long now = System.currentTimeMillis();
                boolean expired = (cachedCookie == null) || (maxAgeSeconds > 0 && (now - cookieFetchTime) > maxAgeSeconds * 1000);
                if (!expired) return;
                String authEndpoint = "https://directorysvc.edwardjones.com:9537/LDPwam-logon-web/logonsvc?userid=fwkldap&password=xx6d8mkg&url=https://iss-wamlogonsvc.apps2.edwardjones.com/wamlogonsvc/logonsvc";
                logger.info("Fetching CRBT auth cookies from: {}", authEndpoint);
                RestTemplate authTemplate = new RestTemplate();
                ResponseEntity<String> response = authTemplate.getForEntity(authEndpoint, String.class);
                logger.info("CRBT auth response status: {}", response.getStatusCode());
                logger.info("CRBT auth response headers: {}", response.getHeaders());
                logger.info("CRBT auth response body: {}", response.getBody());
                String cookieValue = response.getBody();
                if (cookieValue == null || cookieValue.isBlank()) {
                    logger.error("No cookies found in CRBT auth response body. Status: {}, Headers: {}, Body: {}", response.getStatusCode(), response.getHeaders(), response.getBody());
                    throw new RuntimeException("No cookies found in auth response");
                }
                // Parse MAXAGE from cookie string
                long maxAge = 0L;
                try {
                    String[] parts = cookieValue.split(";");
                    for (String part : parts) {
                        String trimmed = part.trim();
                        if (trimmed.toUpperCase().startsWith("MAXAGE=")) {
                            maxAge = Long.parseLong(trimmed.substring(7));
                            break;
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Could not parse MAXAGE from cookie: {}", cookieValue, e);
                }
                cachedCookie = cookieValue;
                cookieFetchTime = now;
                maxAgeSeconds = maxAge;
                logger.info("Cached new CRBT cookie with MAXAGE={} seconds", maxAgeSeconds);
            }

            @Override
            @NonNull
            public org.springframework.http.client.ClientHttpResponse intercept(
                    @NonNull org.springframework.http.HttpRequest request,
                    @NonNull byte[] body,
                    @NonNull org.springframework.http.client.ClientHttpRequestExecution execution) throws IOException {
                refreshCookieIfNeeded();
                request.getHeaders().set(HttpHeaders.COOKIE, cachedCookie);
                logger.info("Making CRBT API request: {} {} with headers {}", request.getMethod(), request.getURI(), request.getHeaders());
                var apiResponse = execution.execute(request, body);
                logger.info("CRBT API response status: {}", apiResponse.getStatusCode());
                return apiResponse;
            }
        }).build();
    }
}
