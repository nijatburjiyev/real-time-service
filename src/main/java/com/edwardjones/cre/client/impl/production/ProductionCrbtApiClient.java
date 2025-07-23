package com.edwardjones.cre.client.impl.production;

import com.edwardjones.cre.client.CrbtApiClient;
import com.edwardjones.cre.model.domain.CrbtTeam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@Profile("!test")
public class ProductionCrbtApiClient implements CrbtApiClient {

    private final RestTemplate restTemplate;

    public ProductionCrbtApiClient(@Qualifier("crbtRestTemplate") RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    @Deprecated
    public List<CrbtTeam> fetchAllTeams() {
        log.warn("DEPRECATED: fetchAllTeams() called. This method uses incorrect bulk fetch logic.");
        return fetchAllTeamsWithMembers().stream()
                .map(response -> {
                    CrbtTeam team = new CrbtTeam();
                    team.setCrbtId(response.crbtID);
                    team.setTeamName(response.teamName);
                    team.setTeamType(response.teamTyCd);
                    team.setActive(response.tmEndDa == null);
                    return team;
                })
                .collect(Collectors.toList());
    }

    @Override
    @Deprecated
    public List<CrbtApiTeamResponse> fetchAllTeamsWithMembers() {
        log.warn("DEPRECATED: fetchAllTeamsWithMembers() called. This method uses incorrect bulk fetch logic and will return empty list.");
        // This method is fundamentally flawed as the API doesn't support bulk fetch
        return Collections.emptyList();
    }

    @Override
    public List<CrbtApiTeamResponse> fetchTeamsForLeader(String pjNumber) {
        // CRITICAL: CRBT API requires UPPERCASE P/J numbers
        String upperCasePjNumber = pjNumber != null ? pjNumber.toUpperCase() : null;
        log.debug("CRBT_API: Fetching teams for leader (idType=J): {} (normalized to: {})", pjNumber, upperCasePjNumber);
        return executeCrbtQuery(upperCasePjNumber, "J");
    }

    @Override
    public List<CrbtApiTeamResponse> fetchTeamDetails(Integer crbtId) {
        log.debug("CRBT_API: Fetching full details for team (idType=C): {}", crbtId);
        return executeCrbtQuery(String.valueOf(crbtId), "C");
    }

    private List<CrbtApiTeamResponse> executeCrbtQuery(String id, String idType) {
        // Additional safety check: ensure PJ numbers are uppercase for CRBT API
        String normalizedId = id;
        if ("J".equals(idType) && id != null) {
            normalizedId = id.toUpperCase();
        }

        String url = UriComponentsBuilder.fromPath("/crt-teams")
                .queryParam("callingPgm", "CRE-Sync-Service")
                .queryParam("id", normalizedId)
                .queryParam("idType", idType)
                .queryParam("returnPopulation", "A")
                .queryParam("returnHistoryCurrent", "C")
                .toUriString();

        try {
            log.debug("CRBT_API: Making request to: {}", url);
            ResponseEntity<List<CrbtApiTeamResponse>> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    null,
                    new ParameterizedTypeReference<>() {}
            );

            List<CrbtApiTeamResponse> teams = response.getBody() != null ? response.getBody() : Collections.emptyList();
            log.debug("CRBT_API: Retrieved {} teams for id='{}', idType='{}'", teams.size(), normalizedId, idType);
            return teams;

        } catch (Exception e) {
            log.error("CRBT_API: Failed to execute query for id='{}', idType='{}'. URL: {}. Error: {}",
                     normalizedId, idType, url, e.getMessage());
            return Collections.emptyList();
        }
    }
}
