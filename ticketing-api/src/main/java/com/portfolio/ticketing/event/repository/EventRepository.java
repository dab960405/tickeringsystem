package com.portfolio.ticketing.event.repository;

import com.portfolio.ticketing.event.domain.Event;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface EventRepository
    extends JpaRepository<Event, UUID>, JpaSpecificationExecutor<Event> {

  // The catalog shows venue name and city for every row: fetch the venue in the same query
  // instead of one extra query per event (N+1).
  @Override
  @EntityGraph(attributePaths = "venue")
  Page<Event> findAll(Specification<Event> specification, Pageable pageable);
}
