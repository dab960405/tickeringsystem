package com.portfolio.ticketing.event.repository;

import com.portfolio.ticketing.event.domain.Event;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EventRepository
    extends JpaRepository<Event, UUID>, JpaSpecificationExecutor<Event> {

  // The catalog shows venue name and city for every row: fetch the venue in the same query
  // instead of one extra query per event (N+1).
  @Override
  @EntityGraph(attributePaths = "venue")
  Page<Event> findAll(Specification<Event> specification, Pageable pageable);

  // Scalar lock reads: returning the status (not a managed entity) keeps the decision tied to
  // the row version read under the lock, independent of the persistence context.
  @Query(value = "SELECT status FROM events WHERE id = :id FOR SHARE", nativeQuery = true)
  Optional<String> findStatusForShare(@Param("id") UUID id);

  @Query(value = "SELECT status FROM events WHERE id = :id FOR UPDATE", nativeQuery = true)
  Optional<String> findStatusForUpdate(@Param("id") UUID id);
}
