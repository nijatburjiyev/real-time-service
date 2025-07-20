package com.edwardjones.cre.repository;

import com.edwardjones.cre.model.domain.UserTeamMembership;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface UserTeamMembershipRepository extends JpaRepository<UserTeamMembership, UserTeamMembership.MembershipId> {
    List<UserTeamMembership> findByUserUsername(String username);
    List<UserTeamMembership> findByTeamCrbtId(Integer crbtId);
}
