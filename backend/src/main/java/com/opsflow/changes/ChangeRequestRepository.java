package com.opsflow.changes;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
public interface ChangeRequestRepository extends JpaRepository<ChangeRequest, Long>, JpaSpecificationExecutor<ChangeRequest> {
    @Override @EntityGraph(attributePaths = {"service", "requester", "relatedIncident"})
    Optional<ChangeRequest> findById(Long id);
    @Override @EntityGraph(attributePaths = {"service", "requester", "relatedIncident"})
    Page<ChangeRequest> findAll(Specification<ChangeRequest> filter, Pageable pageable);
}
