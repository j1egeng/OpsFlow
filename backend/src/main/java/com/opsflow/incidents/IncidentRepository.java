package com.opsflow.incidents;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface IncidentRepository extends JpaRepository<Incident, Long>, JpaSpecificationExecutor<Incident> {
    @Override @EntityGraph(attributePaths = {"service", "reporter", "assignee"})
    Optional<Incident> findById(Long id);
    @Override @EntityGraph(attributePaths = {"service", "reporter", "assignee"})
    Page<Incident> findAll(Specification<Incident> specification, Pageable pageable);

    @Modifying(flushAutomatically = true)
    @Query("update Incident i set i.version = i.version + 1 where i.id = :id and i.version = :expectedVersion")
    int advanceVersion(long id, long expectedVersion);

    @Query("select i.id from Incident i where i.sourceChangeId = :changeId")
    Optional<Long> findIdBySourceChangeId(long changeId);

    @Query(value = "select count(*) from change_requests where id = :changeId and service_id = :serviceId and status = 'FAILED'", nativeQuery = true)
    long countFailedChange(long changeId, long serviceId);
}
