package com.opsflow.changes;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
public interface ChangeHistoryRepository extends JpaRepository<ChangeHistory, Long> {
    @EntityGraph(attributePaths = "changedBy")
    Page<ChangeHistory> findByChangeId(long changeId, Pageable pageable);
}
