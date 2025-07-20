package com.edwardjones.cre.client;

import com.edwardjones.cre.model.domain.CrbtTeam;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;

@Service
public class CrbtApiClient {
    public List<CrbtTeam> fetchAllTeams() {
        // TODO: Implement REST call to GET /crt-teams
        System.out.println("MOCK - Fetching all teams from CRBT API...");
        return new ArrayList<>();
    }
}
