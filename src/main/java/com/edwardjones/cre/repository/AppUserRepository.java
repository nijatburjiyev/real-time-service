package com.edwardjones.cre.repository;

import com.edwardjones.cre.model.domain.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface AppUserRepository extends JpaRepository<AppUser, String> {

    /**
     * Finds a user by their unique employee ID.
     * This will be crucial for linking CRT Kafka events to users.
     *
     * @param employeeId The employee ID to search for.
     * @return An Optional containing the AppUser if found.
     */
    Optional<AppUser> findByEmployeeId(String employeeId);

    /**
     * Finds all users who report to a specific manager.
     * This is the core of our impact analysis for manager changes.
     *
     * @param managerUsername The username (p/j number) of the manager.
     * @return A list of AppUser objects representing the direct reports.
     */
    List<AppUser> findByManagerUsername(String managerUsername);

    /**
     * Finds all active users.
     * Used by reconciliation service to get all managed users.
     *
     * @return A list of all active AppUser objects.
     */
    List<AppUser> findByIsActiveTrue();
}
