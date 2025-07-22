package com.edwardjones.cre.client;

import com.edwardjones.cre.model.domain.CrbtTeam;
import java.util.List;

/**
 * Interface for CRBT API operations.
 * Allows swapping between mock and production implementations.
 */
public interface CrbtApiClient {
    List<CrbtTeam> fetchAllTeams();
    List<CrbtApiTeamResponse> fetchAllTeamsWithMembers();

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
