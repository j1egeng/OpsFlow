package com.opsflow.audit;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long>, JpaSpecificationExecutor<AuditLog> {
    @Override
    @EntityGraph(attributePaths = "actor")
    Optional<AuditLog> findById(Long id);

    @Override
    @EntityGraph(attributePaths = "actor")
    Page<AuditLog> findAll(Specification<AuditLog> specification, Pageable pageable);
}
