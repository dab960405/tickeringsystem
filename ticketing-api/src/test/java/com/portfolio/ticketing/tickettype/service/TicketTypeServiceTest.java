package com.portfolio.ticketing.tickettype.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.ticketing.common.exception.BusinessRuleViolationException;
import com.portfolio.ticketing.common.exception.DuplicateResourceException;
import com.portfolio.ticketing.common.exception.ResourceNotFoundException;
import com.portfolio.ticketing.event.domain.Event;
import com.portfolio.ticketing.event.domain.EventStatus;
import com.portfolio.ticketing.event.service.EventLockService;
import com.portfolio.ticketing.event.service.EventCatalogCacheService;
import com.portfolio.ticketing.event.service.EventService;
import com.portfolio.ticketing.tickettype.api.TicketTypeRequest;
import com.portfolio.ticketing.tickettype.domain.TicketType;
import com.portfolio.ticketing.tickettype.repository.TicketTypeRepository;
import com.portfolio.ticketing.venue.domain.Venue;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TicketTypeServiceTest {

  private static final UUID EVENT_ID = UUID.randomUUID();
  private static final UUID TICKET_TYPE_ID = UUID.randomUUID();
  private static final Instant SALE_START = Instant.parse("2030-01-01T00:00:00Z");
  private static final Instant SALE_END = Instant.parse("2030-01-10T00:00:00Z");

  @Mock private TicketTypeRepository ticketTypeRepository;
  @Mock private EventService eventService;
  @Mock private EventCatalogCacheService eventCatalogCacheService;
  @Mock private EventLockService eventLockService;
  @Mock private EntityManager entityManager;
  @InjectMocks private TicketTypeService ticketTypeService;

  private static Event draftEvent() {
    Event event =
        new Event(
            UUID.randomUUID(),
            new Venue("Arena", "Lima", "Main St 1", 500),
            "Show",
            null,
            "MUSIC",
            Instant.parse("2030-01-10T20:00:00Z"),
            Instant.parse("2030-01-10T23:00:00Z"));
    ReflectionTestUtils.setField(event, "id", EVENT_ID);
    return event;
  }

  private static TicketTypeRequest request(String name, int total) {
    return new TicketTypeRequest(name, new BigDecimal("50.00"), total, SALE_START, SALE_END, 4);
  }

  private static TicketType existing(Event event) {
    return new TicketType(event, "VIP", new BigDecimal("50.00"), 100, SALE_START, SALE_END, 4);
  }

  @Test
  void should_createFullyAvailableTicketType_when_nameIsUnique() {
    when(eventLockService.lockShared(EVENT_ID)).thenReturn(EventStatus.DRAFT);
    when(eventService.findEvent(EVENT_ID)).thenReturn(draftEvent());
    when(ticketTypeRepository.save(any(TicketType.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var created = ticketTypeService.create(EVENT_ID, request("VIP", 100));

    assertThat(created.availableQuantity()).isEqualTo(100);
  }

  @Test
  void should_rejectCreate_when_nameAlreadyExists() {
    when(eventLockService.lockShared(EVENT_ID)).thenReturn(EventStatus.DRAFT);
    when(eventService.findEvent(EVENT_ID)).thenReturn(draftEvent());
    when(ticketTypeRepository.existsByEventIdAndNameIgnoreCase(EVENT_ID, "VIP")).thenReturn(true);

    assertThatThrownBy(() -> ticketTypeService.create(EVENT_ID, request("VIP", 100)))
        .isInstanceOf(DuplicateResourceException.class);
  }

  @Test
  void should_rejectCreate_when_eventIsCancelled() {
    when(eventLockService.lockShared(EVENT_ID)).thenReturn(EventStatus.CANCELLED);

    assertThatThrownBy(() -> ticketTypeService.create(EVENT_ID, request("VIP", 100)))
        .isInstanceOf(BusinessRuleViolationException.class);
  }

  @Test
  void should_skipDuplicateCheck_when_nameIsUnchanged() {
    TicketType ticketType = existing(draftEvent());
    when(ticketTypeRepository.findById(TICKET_TYPE_ID)).thenReturn(Optional.of(ticketType));
    when(eventLockService.lockShared(EVENT_ID)).thenReturn(EventStatus.DRAFT);
    when(ticketTypeRepository.findByIdForUpdate(TICKET_TYPE_ID)).thenReturn(Optional.of(ticketType));
    when(ticketTypeRepository.saveAndFlush(ticketType)).thenReturn(ticketType);

    var updated = ticketTypeService.update(TICKET_TYPE_ID, request("vip", 150));

    assertThat(updated.totalQuantity()).isEqualTo(150);
    verify(ticketTypeRepository, never()).existsByEventIdAndNameIgnoreCase(any(), any());
  }

  @Test
  void should_rejectUpdate_when_renamedToExistingName() {
    TicketType ticketType = existing(draftEvent());
    when(ticketTypeRepository.findById(TICKET_TYPE_ID)).thenReturn(Optional.of(ticketType));
    when(eventLockService.lockShared(EVENT_ID)).thenReturn(EventStatus.DRAFT);
    when(ticketTypeRepository.findByIdForUpdate(TICKET_TYPE_ID)).thenReturn(Optional.of(ticketType));
    when(ticketTypeRepository.existsByEventIdAndNameIgnoreCase(any(), any())).thenReturn(true);

    assertThatThrownBy(() -> ticketTypeService.update(TICKET_TYPE_ID, request("General", 100)))
        .isInstanceOf(DuplicateResourceException.class);
  }

  @Test
  void should_rejectUpdate_when_eventIsCancelled() {
    TicketType ticketType = existing(draftEvent());
    when(ticketTypeRepository.findById(TICKET_TYPE_ID)).thenReturn(Optional.of(ticketType));
    when(eventLockService.lockShared(EVENT_ID)).thenReturn(EventStatus.CANCELLED);

    assertThatThrownBy(() -> ticketTypeService.update(TICKET_TYPE_ID, request("VIP", 100)))
        .isInstanceOf(BusinessRuleViolationException.class);
    verify(ticketTypeRepository, never()).findByIdForUpdate(TICKET_TYPE_ID);
  }

  @Test
  void should_throwNotFound_when_ticketTypeDoesNotExist() {
    when(ticketTypeRepository.findById(TICKET_TYPE_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> ticketTypeService.update(TICKET_TYPE_ID, request("VIP", 100)))
        .isInstanceOf(ResourceNotFoundException.class);
  }
}
