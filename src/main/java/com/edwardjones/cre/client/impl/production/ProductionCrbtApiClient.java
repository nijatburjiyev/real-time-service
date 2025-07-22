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
    public List<CrbtTeam> fetchAllTeams() {
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
    public List<CrbtApiTeamResponse> fetchAllTeamsWithMembers() {
        log.info("Fetching all teams with members from production CRBT API...");

        String url = UriComponentsBuilder.fromPath("")
                .queryParam("callingPgm", "CRE-Sync-Service")
                .queryParam("idType", "A") // 'A' for All
                .queryParam("returnPopulation", "A")
                .toUriString();

        try {
            ResponseEntity<List<CrbtApiTeamResponse>> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    null,
                    new ParameterizedTypeReference<>() {}
            );
            log.info("Successfully retrieved {} teams from CRBT API.", response.getBody() != null ? response.getBody().size() : 0);
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to fetch teams from CRBT API", e);
            return Collections.emptyList();
        }
    }
}
