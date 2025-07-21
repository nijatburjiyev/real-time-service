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
     * Finds all membership records associated with a specific team.
     * Essential for finding all affected users when a team changes.
     *
     * @param crbtId The ID of the CRBT team.
     * @return A list of all memberships for that team.
     */
    @Query("SELECT utm FROM UserTeamMembership utm WHERE utm.id.teamCrbtId = :crbtId")
    List<UserTeamMembership> findByTeamCrbtId(@Param("crbtId") Integer crbtId);

    /**
     * Finds users who are members of multiple teams for analysis.
     * Returns username and count of teams they belong to.
     */
    @Query("SELECT u.username, COUNT(utm.id.teamCrbtId) as teamCount " +
           "FROM UserTeamMembership utm " +
           "JOIN utm.user u " +
           "GROUP BY u.username " +
           "HAVING COUNT(utm.id.teamCrbtId) > 1 " +
           "ORDER BY teamCount DESC")
    List<Object[]> findUsersWithMultipleTeams();

    /**
     * Finds team member counts for analysis.
     * Returns team ID and count of active members.
     */
    @Query("SELECT utm.id.teamCrbtId, COUNT(utm.id.userUsername) as memberCount " +
           "FROM UserTeamMembership utm " +
           "GROUP BY utm.id.teamCrbtId " +
           "ORDER BY memberCount DESC")
    List<Object[]> findTeamMemberCounts();
}
