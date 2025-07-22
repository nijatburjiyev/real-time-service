package com.edwardjones.cre.client;

import com.edwardjones.cre.model.domain.CrbtTeam;
import java.util.List;

/**
 * Interface for CRBT API operations, reflecting the endpoint's lookup-based nature.
 */
public interface CrbtApiClient {

    /**
     * Legacy methods - kept for backward compatibility but deprecated
     */
    @Deprecated
    List<CrbtTeam> fetchAllTeams();

    @Deprecated
    List<CrbtApiTeamResponse> fetchAllTeamsWithMembers();

    /**
     * Fetches teams associated with a specific leader's P/J number.
     * Corresponds to an API call with idType = 'J'.
     *
     * @param pjNumber The P/J number of the leader.
     * @return A list of team responses, which may not have full member lists.
     */
    List<CrbtApiTeamResponse> fetchTeamsForLeader(String pjNumber);

    /**
     * Fetches the full, detailed information for a single team, including its complete member list.
     * Corresponds to an API call with idType = 'C'.
     *
     * @param crbtId The unique ID of the team.
     * @return A list containing the single detailed team response (API returns a list even for one).
     */
    List<CrbtApiTeamResponse> fetchTeamDetails(Integer crbtId);

    /**
     * Data structure that matches the actual CRBT API response format
     */
    class CrbtApiTeamResponse {
        public String teamName;
        public Integer crbtID;
        public String ownerFaNo;
        public String ownerBrNo;
        public String tmBegDa;
        public String tmEndDa;
        public String teamTyCd;
        public String longTeamName;
        public String teamDisplayNameTypeCd;
        public String uidCd;
        public List<CrbtApiMember> memberList;

        public static class CrbtApiMember {
            public String ownerFaNo;
            public String ownerBrNo;
            public String ownerFaName;
            public String teamBegDa;
            public String teamEndDa;
            public String mbrRoleCd;
            public Integer crbtId;
            public String teamTyCd;
            public String mbrJorP;      // Username (p/j number)
            public String mbrEmplId;    // Employee ID
            public String mbrFaNo;      // FA Number
            public String mbrName;
            public String mbrBrNo;
            public String mbrBegDa;
            public String mbrEndDa;
            public Boolean active;
            public String mbrCreUt;
            public String uidCd;
        }
    }
}
