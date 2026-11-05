package com.portfolio.ticketing.venue.repository;

import com.portfolio.ticketing.venue.domain.Venue;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VenueRepository extends JpaRepository<Venue, UUID> {}
