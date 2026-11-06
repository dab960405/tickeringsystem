package com.portfolio.ticketing.tickettype.service;

import com.portfolio.ticketing.common.exception.BusinessRuleViolationException;
import com.portfolio.ticketing.common.exception.DuplicateResourceException;
import com.portfolio.ticketing.common.exception.ResourceNotFoundException;
import com.portfolio.ticketing.event.domain.Event;
import com.portfolio.ticketing.event.service.EventService;
import com.portfolio.ticketing.tickettype.api.TicketTypeRequest;
import com.portfolio.ticketing.tickettype.api.TicketTypeResponse;
import com.portfolio.ticketing.tickettype.domain.TicketType;
import com.portfolio.ticketing.tickettype.repository.TicketTypeRepository;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class TicketTypeService {

  private final TicketTypeRepository ticketTypeRepository;
  private final EventService eventService;

  public TicketTypeService(TicketTypeRepository ticketTypeRepository, EventService eventService) {
    this.ticketTypeRepository = ticketTypeRepository;
    this.eventService = eventService;
  }

  @PreAuthorize("@eventAuthorizationService.canManage(#eventId, authentication)")
  public TicketTypeResponse create(UUID eventId, TicketTypeRequest request) {
    Event event = eventService.findEvent(eventId);
    requireEditable(event);
    requireUniqueName(eventId, request.name());
    TicketType ticketType =
        new TicketType(
            event,
            request.name(),
            request.price(),
            request.totalQuantity(),
            request.saleStartsAt(),
            request.saleEndsAt(),
            request.maxPerOrder());
    return TicketTypeResponse.from(ticketTypeRepository.save(ticketType));
  }

  @PreAuthorize("@ticketTypeAuthorizationService.canManage(#id, authentication)")
  public TicketTypeResponse update(UUID id, TicketTypeRequest request) {
    TicketType ticketType =
        ticketTypeRepository
            .findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Ticket type " + id + " not found"));
    requireEditable(ticketType.getEvent());
    if (!ticketType.getName().equalsIgnoreCase(request.name())) {
      requireUniqueName(ticketType.getEvent().getId(), request.name());
    }
    ticketType.update(
        request.name(),
        request.price(),
        request.totalQuantity(),
        request.saleStartsAt(),
        request.saleEndsAt(),
        request.maxPerOrder());
    return TicketTypeResponse.from(ticketTypeRepository.saveAndFlush(ticketType));
  }

  private static void requireEditable(Event event) {
    if (!event.getStatus().isEditable()) {
      throw new BusinessRuleViolationException(
          "Ticket types of an event in status " + event.getStatus() + " cannot be changed");
    }
  }

  private void requireUniqueName(UUID eventId, String name) {
    if (ticketTypeRepository.existsByEventIdAndNameIgnoreCase(eventId, name)) {
      throw new DuplicateResourceException(
          "Ticket type '" + name + "' already exists for this event");
    }
  }
}
