package com.portfolio.ticketing.tickettype.service;

import com.portfolio.ticketing.tickettype.api.TicketTypeResponse;
import com.portfolio.ticketing.tickettype.repository.TicketTypeAvailabilityProjection;
import com.portfolio.ticketing.tickettype.repository.TicketTypeRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only entry point used by the event module. Catalog metadata is cached separately from live
 * inventory so reservation writes are reflected immediately in every response.
 */
@Service
@Transactional(readOnly = true)
public class TicketTypeQueryService {

  private final TicketTypeRepository ticketTypeRepository;
  private final TicketTypeCatalogCacheService catalogCacheService;

  public TicketTypeQueryService(
      TicketTypeRepository ticketTypeRepository,
      TicketTypeCatalogCacheService catalogCacheService) {
    this.ticketTypeRepository = ticketTypeRepository;
    this.catalogCacheService = catalogCacheService;
  }

  public List<TicketTypeResponse> listForEvent(UUID eventId) {
    var catalog = catalogCacheService.listForEvent(eventId);
    if (catalog.isEmpty()) {
      return List.of();
    }

    Map<UUID, Integer> availability =
        ticketTypeRepository.findAvailabilityByEventId(eventId).stream()
            .collect(
                Collectors.toMap(
                    TicketTypeAvailabilityProjection::getId,
                    TicketTypeAvailabilityProjection::getAvailableQuantity,
                    (first, ignored) -> first));

    return catalog.stream()
        .map(entry -> entry.toResponse(availability.getOrDefault(entry.id(), 0)))
        .toList();
  }

  public boolean existsForEvent(UUID eventId) {
    return ticketTypeRepository.existsByEventId(eventId);
  }
}
