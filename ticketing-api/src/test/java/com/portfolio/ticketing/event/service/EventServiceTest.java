package com.portfolio.ticketing.event.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.portfolio.ticketing.common.exception.BusinessRuleViolationException;
import com.portfolio.ticketing.common.exception.ResourceNotFoundException;
import com.portfolio.ticketing.event.api.EventDetailResponse;
import com.portfolio.ticketing.event.api.EventRequest;
import com.portfolio.ticketing.event.domain.Event;
import com.portfolio.ticketing.event.domain.EventStatus;
import com.portfolio.ticketing.event.repository.EventFilter;
import com.portfolio.ticketing.event.repository.EventRepository;
import com.portfolio.ticketing.tickettype.service.TicketTypeQueryService;
import com.portfolio.ticketing.venue.domain.Venue;
import com.portfolio.ticketing.venue.service.VenueService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;

@ExtendWith(MockitoExtension.class)
class EventServiceTest {

  private static final UUID EVENT_ID = UUID.randomUUID();
  private static final UUID VENUE_ID = UUID.randomUUID();
  private static final UUID ORGANIZER = UUID.randomUUID();
  private static final Instant START = Instant.parse("2030-01-10T20:00:00Z");
  private static final Instant END = Instant.parse("2030-01-10T23:00:00Z");

  @Mock private EventRepository eventRepository;
  @Mock private VenueService venueService;
  @Mock private TicketTypeQueryService ticketTypeQueryService;
  @InjectMocks private EventService eventService;

  private static Venue venue() {
    return new Venue("Arena", "Lima", "Main St 1", 500);
  }

  private static Event draft() {
    return new Event(ORGANIZER, venue(), "Show", null, "MUSIC", START, END);
  }

  private static EventRequest request() {
    return new EventRequest(VENUE_ID, "Show", "Desc", "MUSIC", START, END);
  }

  @Test
  void should_createDraftForOrganizer_when_venueExists() {
    when(venueService.findVenue(VENUE_ID)).thenReturn(venue());
    when(eventRepository.save(any(Event.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    EventDetailResponse created = eventService.create(ORGANIZER, request());

    assertThat(created)
        .extracting(EventDetailResponse::status, EventDetailResponse::organizerId)
        .containsExactly(EventStatus.DRAFT, ORGANIZER);
  }

  @Test
  void should_replaceDetails_when_updated() {
    Event event = draft();
    when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));
    when(venueService.findVenue(VENUE_ID)).thenReturn(venue());
    when(eventRepository.saveAndFlush(event)).thenReturn(event);

    EventDetailResponse updated =
        eventService.update(
            EVENT_ID, new EventRequest(VENUE_ID, "New title", null, "THEATER", START, END));

    assertThat(updated.title()).isEqualTo("New title");
  }

  @Test
  void should_publish_when_eventHasTicketTypes() {
    when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(draft()));
    when(ticketTypeQueryService.existsForEvent(EVENT_ID)).thenReturn(true);

    assertThat(eventService.publish(EVENT_ID).status()).isEqualTo(EventStatus.PUBLISHED);
  }

  @Test
  void should_rejectPublish_when_eventHasNoTicketTypes() {
    when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(draft()));
    when(ticketTypeQueryService.existsForEvent(EVENT_ID)).thenReturn(false);

    assertThatThrownBy(() -> eventService.publish(EVENT_ID))
        .isInstanceOf(BusinessRuleViolationException.class);
  }

  @Test
  void should_cancel_when_eventIsDraft() {
    when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(draft()));

    assertThat(eventService.cancel(EVENT_ID).status()).isEqualTo(EventStatus.CANCELLED);
  }

  @Test
  void should_hideEvent_when_notPublished() {
    when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(draft()));

    assertThatThrownBy(() -> eventService.getPublished(EVENT_ID))
        .isInstanceOf(ResourceNotFoundException.class);
  }

  @Test
  void should_returnDetailWithTicketTypes_when_published() {
    Event event = draft();
    event.publish();
    when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));
    when(ticketTypeQueryService.listForEvent(any())).thenReturn(List.of());

    assertThat(eventService.getPublished(EVENT_ID).status()).isEqualTo(EventStatus.PUBLISHED);
  }

  @Test
  void should_throwNotFound_when_eventDoesNotExist() {
    when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> eventService.findEvent(EVENT_ID))
        .isInstanceOf(ResourceNotFoundException.class);
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_mapCatalogPage_when_listingPublished() {
    Page<Event> page = new PageImpl<>(List.of(draft()), PageRequest.of(0, 20), 1);
    when(eventRepository.findAll(any(Specification.class), any(PageRequest.class)))
        .thenReturn(page);

    var result =
        eventService.listPublished(
            new EventFilter(null, null, null, null, null), PageRequest.of(0, 20));

    assertThat(result.content()).singleElement().extracting("city").isEqualTo("Lima");
  }
}
