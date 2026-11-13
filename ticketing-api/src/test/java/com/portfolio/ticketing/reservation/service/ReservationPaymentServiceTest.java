package com.portfolio.ticketing.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.ticketing.common.exception.InvalidStateTransitionException;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReservationPaymentServiceTest {

  private static final UUID USER_ID = UUID.randomUUID();
  private static final UUID RESERVATION_ID = UUID.randomUUID();
  private static final UUID TICKET_TYPE_ID = UUID.randomUUID();
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
  void should_beIdempotent_when_reservationWasAlreadyConfirmed() {
    Reservation reservation = reservation(ReservationStatus.CONFIRMED);
    stubLockedReservation(reservation);

    assertThat(reservationService.confirmForPayment(RESERVATION_ID, USER_ID))
        .isEqualTo(ReservationConfirmationResult.CONFIRMED);
    verify(reservationRepository, never()).confirmActive(RESERVATION_ID, USER_ID, NOW);
    verify(reservationRepository, never()).confirmRecovered(RESERVATION_ID, USER_ID);
  }

  @Test
  void should_releaseStock_when_paymentFailsForActiveReservation() {
    Reservation reservation = reservation(ReservationStatus.ACTIVE);
    stubLockedReservation(reservation);
    when(reservationRepository.cancelActive(RESERVATION_ID, USER_ID)).thenReturn(1);

    reservationService.releaseForPayment(RESERVATION_ID, USER_ID);

    verify(inventoryService).release(TICKET_TYPE_ID, 2);
  }

  @Test
  void should_notReleaseStockAgain_when_reservationAlreadyExpired() {
    Reservation reservation = reservation(ReservationStatus.EXPIRED);
    stubLockedReservation(reservation);

    reservationService.releaseForPayment(RESERVATION_ID, USER_ID);

    verify(inventoryService, never()).release(TICKET_TYPE_ID, 2);
  }

  @Test
  void should_rejectRelease_when_reservationIsConfirmed() {
    Reservation reservation = reservation(ReservationStatus.CONFIRMED);
    stubLockedReservation(reservation);

    assertThatThrownBy(() -> reservationService.releaseForPayment(RESERVATION_ID, USER_ID))
        .isInstanceOf(InvalidStateTransitionException.class);
  }

  private void stubLockedReservation(Reservation reservation) {
    when(reservationRepository.findByIdAndUserId(RESERVATION_ID, USER_ID))
        .thenReturn(Optional.of(reservation));
    when(reservationRepository.findOwnedForUpdate(RESERVATION_ID, USER_ID))
        .thenReturn(Optional.of(reservation));
    lenient().when(reservationRepository.databaseNow()).thenReturn(NOW.atOffset(ZoneOffset.UTC));
  }

  private static Reservation reservation(ReservationStatus status) {
    TicketType ticketType = org.mockito.Mockito.mock(TicketType.class);
    lenient().when(ticketType.getId()).thenReturn(TICKET_TYPE_ID);
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
