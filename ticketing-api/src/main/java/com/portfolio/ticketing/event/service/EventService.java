package com.portfolio.ticketing.event.service;

import com.portfolio.ticketing.common.api.PageResponse;
import com.portfolio.ticketing.common.exception.BusinessRuleViolationException;
import com.portfolio.ticketing.common.exception.ResourceNotFoundException;
import com.portfolio.ticketing.event.api.EventDetailResponse;
import com.portfolio.ticketing.event.api.EventRequest;
import com.portfolio.ticketing.event.api.EventSummaryResponse;
import com.portfolio.ticketing.event.domain.Event;
import com.portfolio.ticketing.event.repository.EventFilter;
import com.portfolio.ticketing.event.repository.EventRepository;
import com.portfolio.ticketing.event.repository.EventSpecifications;
import com.portfolio.ticketing.tickettype.service.TicketTypeQueryService;
import com.portfolio.ticketing.venue.domain.Venue;
import com.portfolio.ticketing.venue.service.VenueService;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class EventService {

  private final EventRepository eventRepository;
  private final VenueService venueService;
  private final TicketTypeQueryService ticketTypeQueryService;
  private final EventCatalogCacheService eventCatalogCacheService;
  private final EventCancellationService eventCancellationService;

  public EventService(
      EventRepository eventRepository,
      VenueService venueService,
      TicketTypeQueryService ticketTypeQueryService,
      EventCatalogCacheService eventCatalogCacheService,
      EventCancellationService eventCancellationService) {
    this.eventRepository = eventRepository;
    this.venueService = venueService;
    this.ticketTypeQueryService = ticketTypeQueryService;
    this.eventCatalogCacheService = eventCatalogCacheService;
    this.eventCancellationService = eventCancellationService;
  }

  public PageResponse<EventSummaryResponse> listPublished(EventFilter filter, Pageable pageable) {
    if (isPopularFirstPage(filter, pageable)) {
      return eventCatalogCacheService.getPopularFirstPage();
    }
    return PageResponse.from(
        eventRepository
            .findAll(EventSpecifications.publicCatalog(filter), pageable)
            .map(EventSummaryResponse::from));
  }

  /** Public detail: unpublished events are reported as missing so drafts are not disclosed. */
  public EventDetailResponse getPublished(UUID id) {
    return eventCatalogCacheService
        .getPublished(id)
        .withTicketTypes(ticketTypeQueryService.listForEvent(id));
  }

  /** Used by other modules (e.g. ticket types) that need the event entity reference. */
  public Event findEvent(UUID id) {
    return eventRepository.findById(id).orElseThrow(() -> notFound(id));
  }

  @PreAuthorize("hasAnyRole('ORGANIZER', 'ADMIN')")
  @Transactional
  public EventDetailResponse create(UUID organizerId, EventRequest request) {
    Venue venue = venueService.findVenue(request.venueId());
    Event event =
        new Event(
            organizerId,
            venue,
            request.title(),
            request.description(),
            request.category(),
            request.startsAt(),
            request.endsAt());
    EventDetailResponse response = toDetail(eventRepository.save(event));
    eventCatalogCacheService.evictAll();
    return response;
  }

  @PreAuthorize("@eventAuthorizationService.canManage(#id, authentication)")
  @Transactional
  public EventDetailResponse update(UUID id, EventRequest request) {
    Event event = findEvent(id);
    Venue venue = venueService.findVenue(request.venueId());
    event.update(
        venue,
        request.title(),
        request.description(),
        request.category(),
        request.startsAt(),
        request.endsAt());
    EventDetailResponse response = toDetail(eventRepository.saveAndFlush(event));
    eventCatalogCacheService.evictAll();
    return response;
  }

  @PreAuthorize("@eventAuthorizationService.canManage(#id, authentication)")
  @Transactional
  public EventDetailResponse publish(UUID id) {
    Event event = findEvent(id);
    if (!ticketTypeQueryService.existsForEvent(id)) {
      throw new BusinessRuleViolationException(
          "An event needs at least one ticket type before it can be published");
    }
    event.publish();
    EventDetailResponse response = toDetail(event);
    eventCatalogCacheService.evictAll();
    return response;
  }

  /**
   * Cancels the event and resolves active reservations/pending orders atomically. Provider payment
   * state is not changed because event cancellation is not financial evidence.
   */
  @PreAuthorize("@eventAuthorizationService.canManage(#id, authentication)")
  @Transactional
  public EventDetailResponse cancel(UUID id) {
    eventCancellationService.cancel(id);
    EventDetailResponse response = toDetail(findEvent(id));
    eventCatalogCacheService.evictAll();
    return response;
  }

  private static boolean isPopularFirstPage(EventFilter filter, Pageable pageable) {
    return isBlank(filter.q())
        && isBlank(filter.category())
        && isBlank(filter.city())
        && filter.from() == null
        && filter.to() == null
        && pageable.getPageNumber() == 0
        && pageable.getPageSize() == 20
        && pageable.getSort().equals(Sort.by(Sort.Direction.ASC, "startsAt"));
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private EventDetailResponse toDetail(Event event) {
    return EventDetailResponse.from(event, ticketTypeQueryService.listForEvent(event.getId()));
  }

  private static ResourceNotFoundException notFound(UUID id) {
    return new ResourceNotFoundException("Event " + id + " not found");
  }
}
