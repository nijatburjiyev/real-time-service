package com.edwardjones.cre.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestTemplate;

@Configuration
public class ApiClientConfig {

    @Bean
    @Qualifier("vendorRestTemplate")
    public RestTemplate vendorRestTemplate(
            RestTemplateBuilder builder,
            @Value("${app.client.vendor.base-url}") String baseUrl,
            @Value("${app.client.vendor.api-key}") String apiKey
    ) {
        return builder
                .rootUri(baseUrl)
                .additionalInterceptors(apiKeyInterceptor(apiKey))
                .build();
    }

    @Bean
    @Qualifier("crbtRestTemplate")
    public RestTemplate crbtRestTemplate(
            RestTemplateBuilder builder,
            @Value("${app.client.crbt.base-url}") String baseUrl,
            @Value("${app.client.crbt.ejauth-token}") String ejAuthToken,
            @Value("${app.client.crbt.ejpky-token}") String ejPkyToken
    ) {
        return builder
                .rootUri(baseUrl)
                .additionalInterceptors(cookieInterceptor(ejAuthToken, ejPkyToken))
                .build();
    }

    private ClientHttpRequestInterceptor apiKeyInterceptor(String apiKey) {
        return (request, body, execution) -> {
            request.getHeaders().add("X-API-Key", apiKey);
            return execution.execute(request, body);
        };
    }

    private ClientHttpRequestInterceptor cookieInterceptor(String ejAuth, String ejPky) {
        return (request, body, execution) -> {
            request.getHeaders().add("Cookie", "EJAUTH=" + ejAuth);
            request.getHeaders().add("Cookie", "EJPKY=" + ejPky);
            return execution.execute(request, body);
        };
    }
}
