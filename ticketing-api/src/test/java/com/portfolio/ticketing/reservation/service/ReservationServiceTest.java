package com.portfolio.ticketing.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.ticketing.common.exception.BusinessRuleViolationException;
import com.portfolio.ticketing.common.exception.InsufficientStockException;
import com.portfolio.ticketing.common.exception.InvalidStateTransitionException;
import com.portfolio.ticketing.common.exception.ResourceNotFoundException;
import com.portfolio.ticketing.event.domain.Event;
import com.portfolio.ticketing.event.domain.EventStatus;
import com.portfolio.ticketing.reservation.api.ReservationRequest;
import com.portfolio.ticketing.reservation.api.ReservationResponse;
import com.portfolio.ticketing.reservation.config.ReservationProperties;
import com.portfolio.ticketing.reservation.domain.Reservation;
import com.portfolio.ticketing.reservation.domain.ReservationStatus;
import com.portfolio.ticketing.reservation.repository.ReservationRepository;
import com.portfolio.ticketing.tickettype.domain.TicketType;
import com.portfolio.ticketing.tickettype.service.TicketTypeInventoryService;
import com.portfolio.ticketing.venue.domain.Venue;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {

  private static final UUID USER_ID = UUID.randomUUID();
  private static final UUID TICKET_TYPE_ID = UUID.randomUUID();
  private static final UUID RESERVATION_ID = UUID.randomUUID();
  private static final Instant NOW = Instant.parse("2030-01-01T12:00:00Z");

  @Mock private ReservationRepository reservationRepository;
  @Mock private TicketTypeInventoryService inventoryService;

  private ReservationService reservationService;

  @BeforeEach
  void setUp() {
    ReservationProperties properties = new ReservationProperties();
    properties.setTtlMinutes(10);
    reservationService =
        new ReservationService(
            reservationRepository, inventoryService, properties, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void should_createReservationWithConfiguredTtl_when_saleIsOpen() {
    TicketType ticketType = saleableTicketType(EventStatus.PUBLISHED, 4, true);
    when(inventoryService.findRequired(TICKET_TYPE_ID)).thenReturn(ticketType);
    when(reservationRepository.save(any(Reservation.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    ReservationResponse response =
        reservationService.create(USER_ID, new ReservationRequest(TICKET_TYPE_ID, 2));

    assertThat(response)
        .extracting(
            ReservationResponse::ticketTypeId,
            ReservationResponse::quantity,
            ReservationResponse::status,
            ReservationResponse::expiresAt)
        .containsExactly(TICKET_TYPE_ID, 2, ReservationStatus.ACTIVE, NOW.plusSeconds(600));
    verify(inventoryService).reserve(TICKET_TYPE_ID, 2);
  }

  @Test
  void should_rejectReservation_when_eventIsNotPublished() {
    TicketType ticketType = saleableTicketType(EventStatus.DRAFT, 4, true);
    when(inventoryService.findRequired(TICKET_TYPE_ID)).thenReturn(ticketType);

    assertThatThrownBy(
            () -> reservationService.create(USER_ID, new ReservationRequest(TICKET_TYPE_ID, 2)))
        .isInstanceOf(BusinessRuleViolationException.class);
    verify(inventoryService, never()).reserve(any(), any(Integer.class));
  }

  @Test
  void should_rejectReservation_when_saleWindowIsClosed() {
    TicketType ticketType = saleableTicketType(EventStatus.PUBLISHED, 4, false);
    when(inventoryService.findRequired(TICKET_TYPE_ID)).thenReturn(ticketType);

    assertThatThrownBy(
            () -> reservationService.create(USER_ID, new ReservationRequest(TICKET_TYPE_ID, 2)))
        .isInstanceOf(BusinessRuleViolationException.class);
    verify(inventoryService, never()).reserve(any(), any(Integer.class));
  }

  @Test
  void should_rejectReservation_when_quantityExceedsMaxPerOrder() {
    TicketType ticketType = saleableTicketType(EventStatus.PUBLISHED, 1, true);
    when(inventoryService.findRequired(TICKET_TYPE_ID)).thenReturn(ticketType);

    assertThatThrownBy(
            () -> reservationService.create(USER_ID, new ReservationRequest(TICKET_TYPE_ID, 2)))
        .isInstanceOf(BusinessRuleViolationException.class);
    verify(inventoryService, never()).reserve(any(), any(Integer.class));
  }

  @Test
  void should_propagateInsufficientStock_when_atomicUpdateFails() {
    TicketType ticketType = saleableTicketType(EventStatus.PUBLISHED, 4, true);
    when(inventoryService.findRequired(TICKET_TYPE_ID)).thenReturn(ticketType);
    doThrow(new InsufficientStockException("out of stock"))
        .when(inventoryService)
        .reserve(TICKET_TYPE_ID, 2);

    assertThatThrownBy(
            () -> reservationService.create(USER_ID, new ReservationRequest(TICKET_TYPE_ID, 2)))
        .isInstanceOf(InsufficientStockException.class);
    verify(reservationRepository, never()).save(any());
  }

  @Test
  void should_releaseStock_when_ownerCancelsActiveReservation() {
    Reservation reservation = activeReservation();
    when(reservationRepository.findByIdAndUserId(RESERVATION_ID, USER_ID))
        .thenReturn(Optional.of(reservation));
    when(reservationRepository.cancelActive(RESERVATION_ID, USER_ID)).thenReturn(1);

    reservationService.cancel(RESERVATION_ID, USER_ID);

    verify(inventoryService).release(TICKET_TYPE_ID, 2);
  }

  @Test
  void should_returnNotFound_when_reservationBelongsToAnotherUser() {
    when(reservationRepository.findByIdAndUserId(RESERVATION_ID, USER_ID))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> reservationService.cancel(RESERVATION_ID, USER_ID))
        .isInstanceOf(ResourceNotFoundException.class);
    verify(inventoryService, never()).release(any(), any(Integer.class));
  }

  @Test
  void should_rejectCancellation_when_reservationIsTerminal() {
    Reservation reservation = activeReservation();
    reservation.cancel();
    when(reservationRepository.findByIdAndUserId(RESERVATION_ID, USER_ID))
        .thenReturn(Optional.of(reservation));

    assertThatThrownBy(() -> reservationService.cancel(RESERVATION_ID, USER_ID))
        .isInstanceOf(InvalidStateTransitionException.class);
    verify(reservationRepository, never()).cancelActive(any(), any());
  }

  @Test
  void should_returnActiveReservation_when_itBelongsToUserAndHasNotExpired() {
    Reservation reservation = activeReservation();
    when(reservationRepository.findByIdAndUserId(RESERVATION_ID, USER_ID))
        .thenReturn(Optional.of(reservation));

    assertThat(reservationService.findActiveForUser(RESERVATION_ID, USER_ID)).isSameAs(reservation);
  }

  @Test
  void should_rejectActiveReservation_when_itHasExpired() {
    Reservation reservation = new Reservation(USER_ID, ticketTypeMock(), 2, NOW.minusSeconds(1));
    when(reservationRepository.findByIdAndUserId(RESERVATION_ID, USER_ID))
        .thenReturn(Optional.of(reservation));

    assertThatThrownBy(() -> reservationService.findActiveForUser(RESERVATION_ID, USER_ID))
        .isInstanceOf(BusinessRuleViolationException.class);
  }

  private static Reservation activeReservation() {
    return new Reservation(USER_ID, ticketTypeMock(), 2, NOW.plusSeconds(600));
  }

  private static TicketType ticketTypeMock() {
    TicketType ticketType = mock(TicketType.class);
    lenient().when(ticketType.getId()).thenReturn(TICKET_TYPE_ID);
    return ticketType;
  }

  private static TicketType saleableTicketType(
      EventStatus status, int maxPerOrder, boolean onSale) {
    Event event =
        new Event(
            UUID.randomUUID(),
            new Venue("Arena", "Lima", "Main St 1", 500),
            "Show",
            null,
            "MUSIC",
            NOW.plusSeconds(7200),
            NOW.plusSeconds(10800));
    if (status == EventStatus.PUBLISHED) {
      event.publish();
    }
    TicketType ticketType = ticketTypeMock();
    when(ticketType.getEvent()).thenReturn(event);
    lenient().when(ticketType.isOnSale(NOW)).thenReturn(onSale);
    lenient().when(ticketType.getMaxPerOrder()).thenReturn(maxPerOrder);
    return ticketType;
  }
}
