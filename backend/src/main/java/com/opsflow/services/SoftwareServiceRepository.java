package com.opsflow.services;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SoftwareServiceRepository extends JpaRepository<SoftwareService, Long> {
    @Override
    @EntityGraph(attributePaths = "owner")
    Optional<SoftwareService> findById(Long id);

    @Override
    @EntityGraph(attributePaths = "owner")
    Page<SoftwareService> findAll(Pageable pageable);

    @EntityGraph(attributePaths = "owner")
    Page<SoftwareService> findByEnabled(boolean enabled, Pageable pageable);

    boolean existsByCodeIgnoreCase(String code);
    boolean existsByCodeIgnoreCaseAndIdNot(String code, Long id);
}
