package com.portfolio.ticketing.tickettype.service;

import com.portfolio.ticketing.tickettype.api.TicketTypeResponse;
import com.portfolio.ticketing.tickettype.repository.TicketTypeRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only entry point used by the event module. Kept separate from the write service so the
 * dependency between the event and ticket type modules stays one-directional for each concern.
 */
@Service
@Transactional(readOnly = true)
public class TicketTypeQueryService {

  private final TicketTypeRepository ticketTypeRepository;

  public TicketTypeQueryService(TicketTypeRepository ticketTypeRepository) {
    this.ticketTypeRepository = ticketTypeRepository;
  }

  public List<TicketTypeResponse> listForEvent(UUID eventId) {
    return ticketTypeRepository.findByEventIdOrderByPriceAsc(eventId).stream()
        .map(TicketTypeResponse::from)
        .toList();
  }

  public boolean existsForEvent(UUID eventId) {
    return ticketTypeRepository.existsByEventId(eventId);
  }
}
