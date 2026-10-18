package com.opsflow.changes;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
public interface ChangeReviewRepository extends JpaRepository<ChangeReview, Long> {
    @EntityGraph(attributePaths = "reviewer")
    Page<ChangeReview> findByChangeId(long changeId, Pageable pageable);
}
