package com.edwardjones.cre.repository;

import com.edwardjones.cre.model.domain.CrbtTeam;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CrbtTeamRepository extends JpaRepository<CrbtTeam, Integer> {
}
