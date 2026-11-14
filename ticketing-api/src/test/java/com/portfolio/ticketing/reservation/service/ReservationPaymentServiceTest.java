package com.portfolio.ticketing.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.ticketing.event.domain.Event;
import com.portfolio.ticketing.event.domain.EventStatus;
import com.portfolio.ticketing.event.service.EventLockService;
import com.portfolio.ticketing.reservation.config.ReservationProperties;
import com.portfolio.ticketing.reservation.domain.Reservation;
import com.portfolio.ticketing.reservation.domain.ReservationStatus;
import com.portfolio.ticketing.reservation.repository.ReservationRepository;
import com.portfolio.ticketing.tickettype.domain.TicketType;
import com.portfolio.ticketing.tickettype.service.TicketTypeInventoryService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReservationPaymentServiceTest {

  private static final UUID USER_ID = UUID.randomUUID();
  private static final UUID RESERVATION_ID = UUID.randomUUID();
  private static final UUID TICKET_TYPE_ID = UUID.randomUUID();
  private static final UUID EVENT_ID = UUID.randomUUID();
  private static final Instant NOW = Instant.parse("2030-01-01T12:00:00Z");

  @Mock private ReservationRepository reservationRepository;
  @Mock private TicketTypeInventoryService inventoryService;
  @Mock private EventLockService eventLockService;

  private ReservationService reservationService;

  @BeforeEach
  void setUp() {
    ReservationProperties properties = new ReservationProperties();
    properties.setTtlMinutes(10);
    reservationService =
        new ReservationService(
            reservationRepository,
            inventoryService,
            eventLockService,
            properties,
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void should_confirmActiveReservation_withoutTakingStockAgain() {
    Reservation reservation = reservation(ReservationStatus.ACTIVE);
    stubLockedReservation(reservation);
    when(reservationRepository.confirmActive(RESERVATION_ID, USER_ID, NOW)).thenReturn(1);

    ReservationConfirmationResult result =
        reservationService.confirmForPayment(RESERVATION_ID, USER_ID);

    assertThat(result).isEqualTo(ReservationConfirmationResult.CONFIRMED);
    verify(inventoryService, never()).tryReserve(TICKET_TYPE_ID, 2);
  }

  @Test
  void should_recoverStock_when_expiredReservationCanBePaid() {
    Reservation reservation = reservation(ReservationStatus.EXPIRED);
    stubLockedReservation(reservation);
    when(inventoryService.tryReserve(TICKET_TYPE_ID, 2)).thenReturn(true);
    when(reservationRepository.confirmRecovered(RESERVATION_ID, USER_ID)).thenReturn(1);

    ReservationConfirmationResult result =
        reservationService.confirmForPayment(RESERVATION_ID, USER_ID);

    assertThat(result).isEqualTo(ReservationConfirmationResult.CONFIRMED);
    verify(inventoryService).tryReserve(TICKET_TYPE_ID, 2);
  }

  @Test
  void should_reportOutOfStock_when_expiredReservationCannotBeRecovered() {
    Reservation reservation = reservation(ReservationStatus.EXPIRED);
    stubLockedReservation(reservation);
    when(inventoryService.tryReserve(TICKET_TYPE_ID, 2)).thenReturn(false);

    ReservationConfirmationResult result =
        reservationService.confirmForPayment(RESERVATION_ID, USER_ID);

    assertThat(result).isEqualTo(ReservationConfirmationResult.OUT_OF_STOCK);
    verify(reservationRepository, never()).confirmRecovered(RESERVATION_ID, USER_ID);
  }

  @Test
  void should_rejectRecovery_when_graceWindowHasElapsed() {
    Reservation reservation = reservation(ReservationStatus.EXPIRED);
    org.springframework.test.util.ReflectionTestUtils.setField(
        reservation, "expiresAt", NOW.minusSeconds(901));
    stubLockedReservation(reservation);

    assertThat(reservationService.confirmForPayment(RESERVATION_ID, USER_ID))
        .isEqualTo(ReservationConfirmationResult.RECOVERY_NOT_ALLOWED);
    verify(inventoryService, never()).tryReserve(TICKET_TYPE_ID, 2);
  }

  @Test
  void should_rejectRecovery_when_eventHasStarted() {
    Reservation reservation = reservation(ReservationStatus.EXPIRED);
    when(reservation.getTicketType().getEvent().getStartsAt()).thenReturn(NOW);
    stubLockedReservation(reservation);

    assertThat(reservationService.confirmForPayment(RESERVATION_ID, USER_ID))
        .isEqualTo(ReservationConfirmationResult.RECOVERY_NOT_ALLOWED);
    verify(inventoryService, never()).tryReserve(TICKET_TYPE_ID, 2);
  }

  @Test
  void should_rejectRecovery_when_reservationWasCancelled() {
    Reservation reservation = reservation(ReservationStatus.CANCELLED);
    stubLockedReservation(reservation);

    assertThat(reservationService.confirmForPayment(RESERVATION_ID, USER_ID))
        .isEqualTo(ReservationConfirmationResult.RECOVERY_NOT_ALLOWED);
    verify(inventoryService, never()).tryReserve(TICKET_TYPE_ID, 2);
  }

  @Test
  void should_beIdempotent_when_reservationWasAlreadyConfirmed() {
    Reservation reservation = reservation(ReservationStatus.CONFIRMED);
    stubLockedReservation(reservation);

    assertThat(reservationService.confirmForPayment(RESERVATION_ID, USER_ID))
        .isEqualTo(ReservationConfirmationResult.CONFIRMED);
    verify(reservationRepository, never()).confirmActive(RESERVATION_ID, USER_ID, NOW);
    verify(reservationRepository, never()).confirmRecovered(RESERVATION_ID, USER_ID);
  }

  @Test
  void should_lockEventSharedBeforeTicketTypeAndReservation_when_confirming() {
    Reservation reservation = reservation(ReservationStatus.ACTIVE);
    stubLockedReservation(reservation);
    when(reservationRepository.confirmActive(RESERVATION_ID, USER_ID, NOW)).thenReturn(1);

    reservationService.confirmForPayment(RESERVATION_ID, USER_ID);

    InOrder lockOrder = inOrder(eventLockService, inventoryService, reservationRepository);
    lockOrder.verify(eventLockService).lockShared(EVENT_ID);
    lockOrder.verify(inventoryService).lockRequired(TICKET_TYPE_ID);
    lockOrder.verify(reservationRepository).findOwnedForUpdate(RESERVATION_ID, USER_ID);
  }

  @Test
  void should_releaseHeldStockWithoutConfirming_when_eventIsNoLongerPublished() {
    Reservation reservation = reservation(ReservationStatus.ACTIVE);
    stubLockedReservation(reservation, EventStatus.CANCELLED);
    when(reservationRepository.cancelActive(RESERVATION_ID, USER_ID)).thenReturn(1);

    assertThat(reservationService.confirmForPayment(RESERVATION_ID, USER_ID))
        .isEqualTo(ReservationConfirmationResult.RECOVERY_NOT_ALLOWED);
    verify(inventoryService).release(TICKET_TYPE_ID, 2);
    verify(reservationRepository, never()).confirmActive(any(), any(), any());
  }

  @Test
  void should_notRecoverExpiredReservation_when_eventWasCancelledWithinGrace() {
    Reservation reservation = reservation(ReservationStatus.EXPIRED);
    stubLockedReservation(reservation, EventStatus.CANCELLED);

    assertThat(reservationService.confirmForPayment(RESERVATION_ID, USER_ID))
        .isEqualTo(ReservationConfirmationResult.RECOVERY_NOT_ALLOWED);
    verify(inventoryService, never()).tryReserve(any(), anyInt());
    verify(reservationRepository, never()).confirmRecovered(any(), any());
  }

  @Test
  void should_keepConfirmedResult_when_eventWasCancelledAfterConfirmation() {
    Reservation reservation = reservation(ReservationStatus.CONFIRMED);
    stubLockedReservation(reservation, EventStatus.CANCELLED);

    assertThat(reservationService.confirmForPayment(RESERVATION_ID, USER_ID))
        .isEqualTo(ReservationConfirmationResult.CONFIRMED);
    verify(inventoryService, never()).release(any(), anyInt());
  }

  @Test
  void should_releaseStock_when_paymentFailsForActiveReservation() {
    Reservation reservation = reservation(ReservationStatus.ACTIVE);
    stubLockedReservation(reservation);
    when(reservationRepository.cancelActive(RESERVATION_ID, USER_ID)).thenReturn(1);

    assertThat(reservationService.releaseForPayment(RESERVATION_ID, USER_ID))
        .isEqualTo(ReservationReleaseResult.RELEASED);
    verify(inventoryService).release(TICKET_TYPE_ID, 2);
  }

  @Test
  void should_notReleaseStockAgain_when_reservationAlreadyExpired() {
    Reservation reservation = reservation(ReservationStatus.EXPIRED);
    stubLockedReservation(reservation);

    assertThat(reservationService.releaseForPayment(RESERVATION_ID, USER_ID))
        .isEqualTo(ReservationReleaseResult.ALREADY_RELEASED);
    verify(inventoryService, never()).release(TICKET_TYPE_ID, 2);
  }

  @Test
  void should_reportConfirmedReservation_withoutThrowing_when_releaseIsAttempted() {
    Reservation reservation = reservation(ReservationStatus.CONFIRMED);
    stubLockedReservation(reservation);

    assertThat(reservationService.releaseForPayment(RESERVATION_ID, USER_ID))
        .isEqualTo(ReservationReleaseResult.CONFIRMED);
    verify(inventoryService, never()).release(any(), anyInt());
  }

  private void stubLockedReservation(Reservation reservation) {
    stubLockedReservation(reservation, EventStatus.PUBLISHED);
  }

  private void stubLockedReservation(Reservation reservation, EventStatus lockedEventStatus) {
    when(reservationRepository.findTicketTypeIdByIdAndUserId(RESERVATION_ID, USER_ID))
        .thenReturn(Optional.of(TICKET_TYPE_ID));
    lenient().when(inventoryService.findEventId(TICKET_TYPE_ID)).thenReturn(EVENT_ID);
    lenient().when(eventLockService.lockShared(EVENT_ID)).thenReturn(lockedEventStatus);
    when(reservationRepository.findOwnedForUpdate(RESERVATION_ID, USER_ID))
        .thenReturn(Optional.of(reservation));
    lenient().when(reservationRepository.databaseNow()).thenReturn(NOW);
  }

  private static Reservation reservation(ReservationStatus status) {
    TicketType ticketType = org.mockito.Mockito.mock(TicketType.class);
    Event event = org.mockito.Mockito.mock(Event.class);
    lenient().when(ticketType.getId()).thenReturn(TICKET_TYPE_ID);
    lenient().when(ticketType.getEvent()).thenReturn(event);
    lenient().when(event.getStartsAt()).thenReturn(NOW.plusSeconds(3600));
    Reservation reservation = new Reservation(USER_ID, ticketType, 2, NOW.plusSeconds(600));
    switch (status) {
      case ACTIVE -> {}
      case CONFIRMED -> reservation.confirm();
      case EXPIRED -> reservation.expire();
      case CANCELLED -> reservation.cancel();
    }
    return reservation;
  }
}
