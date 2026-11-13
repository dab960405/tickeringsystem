package com.portfolio.ticketing.reservation.service;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.portfolio.ticketing.reservation.domain.Reservation;
import com.portfolio.ticketing.reservation.repository.ReservationRepository;
import com.portfolio.ticketing.tickettype.domain.TicketType;
import com.portfolio.ticketing.tickettype.service.TicketTypeInventoryService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ReservationExpirationServiceTest {

  private static final Instant NOW = Instant.parse("2030-01-01T12:00:00Z");
  private static final UUID TICKET_TYPE_ID = UUID.randomUUID();
  private static final UUID RESERVATION_ID = UUID.randomUUID();

  @Mock private ReservationRepository reservationRepository;
  @Mock private TicketTypeInventoryService inventoryService;

  private ReservationExpirationService expirationService;

  @BeforeEach
  void setUp() {
    expirationService =
        new ReservationExpirationService(
            reservationRepository, inventoryService, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void should_returnStockAfterOrderedLocksAndDatabaseRevalidation() {
    Reservation reservation = expiredReservation(NOW.minusSeconds(1));
    stubCandidates(reservation);
    when(reservationRepository.expireActive(RESERVATION_ID, NOW)).thenReturn(1);

    expirationService.expireOverdue();

    InOrder order = inOrder(inventoryService, reservationRepository);
    order.verify(inventoryService).lockAll(List.of(TICKET_TYPE_ID));
    order.verify(reservationRepository).findAllByIdForUpdate(List.of(RESERVATION_ID));
    order.verify(reservationRepository).databaseNow();
    order.verify(reservationRepository).expireActive(RESERVATION_ID, NOW);
    order.verify(inventoryService).release(TICKET_TYPE_ID, 2);
  }

  @Test
  void should_notReturnStock_when_reservationWasAlreadyProcessed() {
    Reservation reservation = expiredReservation(NOW.minusSeconds(1));
    stubCandidates(reservation);
    when(reservationRepository.expireActive(RESERVATION_ID, NOW)).thenReturn(0);

    expirationService.expireOverdue();

    verify(inventoryService, never()).release(TICKET_TYPE_ID, 2);
  }

  @Test
  void should_useBoundedBatch_when_noReservationIsOverdue() {
    when(reservationRepository.findExpiredCandidates(NOW, 100)).thenReturn(List.of());

    expirationService.expireOverdue();

    verify(reservationRepository).findExpiredCandidates(NOW, 100);
    verifyNoInteractions(inventoryService);
  }

  @Test
  void should_notExpire_when_databaseTimePrecedesExpiration() {
    Reservation reservation = expiredReservation(NOW.plusSeconds(1));
    stubCandidates(reservation);

    expirationService.expireOverdue();

    verify(reservationRepository, never()).expireActive(RESERVATION_ID, NOW);
    verify(inventoryService, never()).release(TICKET_TYPE_ID, 2);
  }

  private void stubCandidates(Reservation reservation) {
    when(reservationRepository.findExpiredCandidates(NOW, 100)).thenReturn(List.of(reservation));
    when(reservationRepository.findAllByIdForUpdate(List.of(RESERVATION_ID)))
        .thenReturn(List.of(reservation));
    when(reservationRepository.databaseNow()).thenReturn(NOW.atOffset(ZoneOffset.UTC));
  }

  private static Reservation expiredReservation(Instant expiresAt) {
    TicketType ticketType = mock(TicketType.class);
    lenient().when(ticketType.getId()).thenReturn(TICKET_TYPE_ID);
    Reservation reservation = new Reservation(UUID.randomUUID(), ticketType, 2, expiresAt);
    ReflectionTestUtils.setField(reservation, "id", RESERVATION_ID);
    return reservation;
  }
}
