package com.opsflow.users;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface UserRepository extends JpaRepository<UserAccount, Long> {
    @EntityGraph(attributePaths = "roleAssignments.role")
    Optional<UserAccount> findByUsernameIgnoreCase(String username);

    @Override
    @EntityGraph(attributePaths = "roleAssignments.role")
    Optional<UserAccount> findById(Long id);

    boolean existsByUsernameIgnoreCase(String username);
    boolean existsByEmailIgnoreCase(String email);

    @Query("select u from UserAccount u where u.enabled = true and exists "
            + "(select a from UserRole a where a.user = u and a.role.code in "
            + "(com.opsflow.users.RoleCode.ADMINISTRATOR, com.opsflow.users.RoleCode.DEVELOPER))")
    Page<UserAccount> findIncidentAssignees(Pageable pageable);

    @Query("select count(distinct u.id) from UserAccount u join u.roleAssignments a "
            + "where u.enabled = true and a.role.code = :code")
    long countEnabledWithRole(RoleCode code);
}
