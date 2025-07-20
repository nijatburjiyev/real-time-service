package com.edwardjones.cre.repository;

import com.edwardjones.cre.model.domain.CrbtTeam;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository for managing CrbtTeam entities.
 * Provides basic CRUD operations for CRBT team data.
 *
 * The CrbtTeam represents compliance review and business teams
 * that users can be members of, affecting their access profiles.
 */
@Repository
public interface CrbtTeamRepository extends JpaRepository<CrbtTeam, Integer> {
    // Basic CRUD operations provided by JpaRepository are sufficient for now
    // Additional custom queries can be added here as needed
}
