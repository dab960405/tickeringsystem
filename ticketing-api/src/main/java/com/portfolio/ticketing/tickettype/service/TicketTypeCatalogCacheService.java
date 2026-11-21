package com.portfolio.ticketing.tickettype.service;

import com.portfolio.ticketing.common.config.RedisCacheConfig;
import com.portfolio.ticketing.tickettype.api.TicketTypeCatalogEntry;
import com.portfolio.ticketing.tickettype.repository.TicketTypeRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class TicketTypeCatalogCacheService {

  public static final String CACHE_NAME = "ticket-types-catalog";

  private final TicketTypeRepository ticketTypeRepository;

  public TicketTypeCatalogCacheService(TicketTypeRepository ticketTypeRepository) {
    this.ticketTypeRepository = ticketTypeRepository;
  }

  @Cacheable(
      cacheNames = CACHE_NAME,
      key = "#eventId",
      cacheManager = RedisCacheConfig.CATALOG_READ_CACHE_MANAGER)
  public List<TicketTypeCatalogEntry> listForEvent(UUID eventId) {
    return ticketTypeRepository.findByEventIdOrderByPriceAsc(eventId).stream()
        .map(TicketTypeCatalogEntry::from)
        .toList();
  }
}
