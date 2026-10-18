package com.opsflow.incidents;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IncidentHistoryRepository extends JpaRepository<IncidentHistory, Long> {
    @EntityGraph(attributePaths = "changedBy")
    Page<IncidentHistory> findByIncidentId(long incidentId, Pageable pageable);
}
