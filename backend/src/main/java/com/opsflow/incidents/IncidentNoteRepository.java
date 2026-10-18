package com.opsflow.incidents;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IncidentNoteRepository extends JpaRepository<IncidentNote, Long> {
    @EntityGraph(attributePaths = "author")
    Page<IncidentNote> findByIncidentId(long incidentId, Pageable pageable);
}
