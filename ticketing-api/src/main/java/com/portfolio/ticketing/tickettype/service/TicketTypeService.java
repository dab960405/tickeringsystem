package com.portfolio.ticketing.tickettype.service;

import com.portfolio.ticketing.common.exception.BusinessRuleViolationException;
import com.portfolio.ticketing.common.exception.DuplicateResourceException;
import com.portfolio.ticketing.common.exception.ResourceNotFoundException;
import com.portfolio.ticketing.event.domain.Event;
import com.portfolio.ticketing.event.domain.EventStatus;
import com.portfolio.ticketing.event.service.EventCatalogCacheService;
import com.portfolio.ticketing.event.service.EventLockService;
import com.portfolio.ticketing.event.service.EventService;
import com.portfolio.ticketing.tickettype.api.TicketTypeRequest;
import com.portfolio.ticketing.tickettype.api.TicketTypeResponse;
import com.portfolio.ticketing.tickettype.domain.TicketType;
import com.portfolio.ticketing.tickettype.repository.TicketTypeRepository;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class TicketTypeService {

  private final TicketTypeRepository ticketTypeRepository;
  private final EventService eventService;
  private final EventCatalogCacheService eventCatalogCacheService;
  private final EventLockService eventLockService;
  private final EntityManager entityManager;

  public TicketTypeService(
      TicketTypeRepository ticketTypeRepository,
      EventService eventService,
      EventCatalogCacheService eventCatalogCacheService,
      EventLockService eventLockService,
      EntityManager entityManager) {
    this.ticketTypeRepository = ticketTypeRepository;
    this.eventService = eventService;
    this.eventCatalogCacheService = eventCatalogCacheService;
    this.eventLockService = eventLockService;
    this.entityManager = entityManager;
  }

  @PreAuthorize("@eventAuthorizationService.canManage(#eventId, authentication)")
  @CacheEvict(cacheNames = TicketTypeCatalogCacheService.CACHE_NAME, allEntries = true)
  public TicketTypeResponse create(UUID eventId, TicketTypeRequest request) {
    requireEditable(eventLockService.lockShared(eventId));
    Event event = eventService.findEvent(eventId);
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
    TicketTypeResponse response = TicketTypeResponse.from(ticketTypeRepository.save(ticketType));
    eventCatalogCacheService.evictAll();
    return response;
  }

  @PreAuthorize("@ticketTypeAuthorizationService.canManage(#id, authentication)")
  @CacheEvict(cacheNames = TicketTypeCatalogCacheService.CACHE_NAME, allEntries = true)
  public TicketTypeResponse update(UUID id, TicketTypeRequest request) {
    TicketType ticketType =
        ticketTypeRepository
            .findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Ticket type " + id + " not found"));
    UUID eventId = ticketType.getEvent().getId();
    requireEditable(eventLockService.lockShared(eventId));
    ticketType =
        ticketTypeRepository
            .findByIdForUpdate(id)
            .orElseThrow(() -> new ResourceNotFoundException("Ticket type " + id + " not found"));
    entityManager.refresh(ticketType);
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
    TicketTypeResponse response =
        TicketTypeResponse.from(ticketTypeRepository.saveAndFlush(ticketType));
    eventCatalogCacheService.evictAll();
    return response;
  }

  private static void requireEditable(EventStatus status) {
    if (!status.isEditable()) {
      throw new BusinessRuleViolationException(
          "Ticket types of an event in status " + status + " cannot be changed");
    }
  }

  private void requireUniqueName(UUID eventId, String name) {
    if (ticketTypeRepository.existsByEventIdAndNameIgnoreCase(eventId, name)) {
      throw new DuplicateResourceException(
          "Ticket type '" + name + "' already exists for this event");
    }
  }
}
