package com.portfolio.ticketing.tickettype.service;

import com.portfolio.ticketing.event.service.EventAuthorizationService;
import com.portfolio.ticketing.tickettype.repository.TicketTypeRepository;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service("ticketTypeAuthorizationService")
@Transactional(readOnly = true)
public class TicketTypeAuthorizationService {

  private final TicketTypeRepository ticketTypeRepository;
  private final EventAuthorizationService eventAuthorizationService;

  public TicketTypeAuthorizationService(
      TicketTypeRepository ticketTypeRepository,
      EventAuthorizationService eventAuthorizationService) {
    this.ticketTypeRepository = ticketTypeRepository;
    this.eventAuthorizationService = eventAuthorizationService;
  }

  public boolean canManage(UUID ticketTypeId, Authentication authentication) {
    return ticketTypeRepository
        .findById(ticketTypeId)
        .map(ticketType ->
            eventAuthorizationService.canManage(ticketType.getEvent().getId(), authentication))
        // Let the business service return 404 for an unknown ticket type.
        .orElse(true);
  }
}
