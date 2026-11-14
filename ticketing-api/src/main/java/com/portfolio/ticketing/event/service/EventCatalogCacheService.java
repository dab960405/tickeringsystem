package com.portfolio.ticketing.event.service;

import com.portfolio.ticketing.common.api.PageResponse;
import com.portfolio.ticketing.common.config.RedisCacheConfig;
import com.portfolio.ticketing.common.exception.ResourceNotFoundException;
import com.portfolio.ticketing.event.api.EventMetadata;
import com.portfolio.ticketing.event.api.EventSummaryResponse;
import com.portfolio.ticketing.event.domain.Event;
import com.portfolio.ticketing.event.domain.EventStatus;
import com.portfolio.ticketing.event.repository.EventFilter;
import com.portfolio.ticketing.event.repository.EventRepository;
import com.portfolio.ticketing.event.repository.EventSpecifications;
import java.util.UUID;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class EventCatalogCacheService {

  public static final String DETAIL_CACHE = "event-details";
  public static final String POPULAR_CACHE = "event-popular";
  public static final String POPULAR_KEY = "initial";

  private final EventRepository eventRepository;

  public EventCatalogCacheService(EventRepository eventRepository) {
    this.eventRepository = eventRepository;
  }

  @Cacheable(
      cacheNames = DETAIL_CACHE,
      key = "#id",
      cacheManager = RedisCacheConfig.CATALOG_READ_CACHE_MANAGER)
  public EventMetadata getPublished(UUID id) {
    Event event =
        eventRepository
            .findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Event " + id + " not found"));
    if (event.getStatus() != EventStatus.PUBLISHED) {
      throw new ResourceNotFoundException("Event " + id + " not found");
    }
    return EventMetadata.from(event);
  }

  @Cacheable(
      cacheNames = POPULAR_CACHE,
      key = "'" + POPULAR_KEY + "'",
      cacheManager = RedisCacheConfig.CATALOG_READ_CACHE_MANAGER)
  public PageResponse<EventSummaryResponse> getPopularFirstPage() {
    return PageResponse.from(
        eventRepository
            .findAll(
                EventSpecifications.publicCatalog(new EventFilter(null, null, null, null, null)),
                PageRequest.of(0, 20, Sort.by(Sort.Direction.ASC, "startsAt")))
            .map(EventSummaryResponse::from));
  }

  @CacheEvict(
      cacheNames = {DETAIL_CACHE, POPULAR_CACHE},
      allEntries = true)
  public void evictAll() {}
}
