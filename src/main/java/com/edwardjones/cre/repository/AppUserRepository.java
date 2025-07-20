package com.edwardjones.cre.repository;

import com.edwardjones.cre.model.domain.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface AppUserRepository extends JpaRepository<AppUser, String> {
    AppUser findByEmployeeId(String employeeId);
    List<AppUser> findByManagerUsername(String managerUsername);
}
