package com.portfolio.ticketing.event.service;

import com.portfolio.ticketing.common.api.PageResponse;
import com.portfolio.ticketing.common.exception.BusinessRuleViolationException;
import com.portfolio.ticketing.common.exception.ResourceNotFoundException;
import com.portfolio.ticketing.event.api.EventDetailResponse;
import com.portfolio.ticketing.event.api.EventRequest;
import com.portfolio.ticketing.event.api.EventSummaryResponse;
import com.portfolio.ticketing.event.domain.Event;
import com.portfolio.ticketing.event.domain.EventStatus;
import com.portfolio.ticketing.event.repository.EventFilter;
import com.portfolio.ticketing.event.repository.EventRepository;
import com.portfolio.ticketing.event.repository.EventSpecifications;
import com.portfolio.ticketing.tickettype.service.TicketTypeQueryService;
import com.portfolio.ticketing.venue.domain.Venue;
import com.portfolio.ticketing.venue.service.VenueService;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class EventService {

  private final EventRepository eventRepository;
  private final VenueService venueService;
  private final TicketTypeQueryService ticketTypeQueryService;

  public EventService(
      EventRepository eventRepository,
      VenueService venueService,
      TicketTypeQueryService ticketTypeQueryService) {
    this.eventRepository = eventRepository;
    this.venueService = venueService;
    this.ticketTypeQueryService = ticketTypeQueryService;
  }

  public PageResponse<EventSummaryResponse> listPublished(EventFilter filter, Pageable pageable) {
    return PageResponse.from(
        eventRepository
            .findAll(EventSpecifications.publicCatalog(filter), pageable)
            .map(EventSummaryResponse::from));
  }

  /** Public detail: unpublished events are reported as missing so drafts are not disclosed. */
  public EventDetailResponse getPublished(UUID id) {
    Event event = findEvent(id);
    if (event.getStatus() != EventStatus.PUBLISHED) {
      throw notFound(id);
    }
    return toDetail(event);
  }

  /** Used by other modules (e.g. ticket types) that need the event entity reference. */
  public Event findEvent(UUID id) {
    return eventRepository.findById(id).orElseThrow(() -> notFound(id));
  }

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
    return toDetail(eventRepository.save(event));
  }

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
    return toDetail(eventRepository.saveAndFlush(event));
  }

  @Transactional
  public EventDetailResponse publish(UUID id) {
    Event event = findEvent(id);
    if (!ticketTypeQueryService.existsForEvent(id)) {
      throw new BusinessRuleViolationException(
          "An event needs at least one ticket type before it can be published");
    }
    event.publish();
    return toDetail(event);
  }

  // Expiring the event's active reservations and emitting EventCancelled arrive with the
  // reservation (phase 3) and outbox (phase 4) modules.
  @Transactional
  public EventDetailResponse cancel(UUID id) {
    Event event = findEvent(id);
    event.cancel();
    return toDetail(event);
  }

  private EventDetailResponse toDetail(Event event) {
    return EventDetailResponse.from(event, ticketTypeQueryService.listForEvent(event.getId()));
  }

  private static ResourceNotFoundException notFound(UUID id) {
    return new ResourceNotFoundException("Event " + id + " not found");
  }
}
