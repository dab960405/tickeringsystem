package com.portfolio.ticketing.tickettype.repository;

import com.portfolio.ticketing.tickettype.domain.TicketType;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketTypeRepository extends JpaRepository<TicketType, UUID> {

  List<TicketType> findByEventIdOrderByPriceAsc(UUID eventId);

  boolean existsByEventId(UUID eventId);

  boolean existsByEventIdAndNameIgnoreCase(UUID eventId, String name);
}
