package com.edwardjones.cre.repository;

import com.edwardjones.cre.model.domain.UserTeamMembership;
import com.edwardjones.cre.model.domain.UserTeamMembershipId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;

/**
 * Repository for managing UserTeamMembership entities.
 * Handles the many-to-many relationship between users and CRBT teams.
 *
 * This repository is essential for impact analysis when teams change,
 * as it allows us to find all affected users and recalculate their configurations.
 */
@Repository
public interface UserTeamMembershipRepository extends JpaRepository<UserTeamMembership, UserTeamMembershipId> {

    /**
     * Finds all memberships for a specific user.
     * Used to determine which teams a user belongs to for configuration calculation.
     *
     * @param username The username (p/j number) of the user.
     * @return A list of UserTeamMembership objects for that user.
     */
    @Query("SELECT utm FROM UserTeamMembership utm WHERE utm.id.userUsername = :username")
    List<UserTeamMembership> findByUserUsername(@Param("username") String username);

    /**
     * Finds all memberships for a specific team.
     * Used to determine blast radius when a team changes.
     *
     * @param teamId The CRBT team ID.
     * @return A list of UserTeamMembership objects for that team.
     */
    @Query("SELECT utm FROM UserTeamMembership utm WHERE utm.id.teamCrbtId = :teamId")
    List<UserTeamMembership> findByTeamCrbtId(@Param("teamId") Integer teamId);
}
