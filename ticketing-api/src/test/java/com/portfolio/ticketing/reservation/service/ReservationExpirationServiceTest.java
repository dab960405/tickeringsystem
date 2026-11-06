package com.portfolio.ticketing.reservation.service;

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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReservationExpirationServiceTest {

  private static final Instant NOW = Instant.parse("2030-01-01T12:00:00Z");
  private static final UUID TICKET_TYPE_ID = UUID.randomUUID();

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
  void should_returnStock_when_expiredReservationIsMarked() {
    Reservation reservation = expiredReservation();
    when(reservationRepository.findExpiredForUpdate(NOW, 100)).thenReturn(List.of(reservation));
    when(reservationRepository.expireActive(reservation.getId(), NOW)).thenReturn(1);

    expirationService.expireOverdue();

    verify(inventoryService).release(TICKET_TYPE_ID, 2);
  }

  @Test
  void should_notReturnStock_when_reservationWasAlreadyProcessed() {
    Reservation reservation = expiredReservation();
    when(reservationRepository.findExpiredForUpdate(NOW, 100)).thenReturn(List.of(reservation));
    when(reservationRepository.expireActive(reservation.getId(), NOW)).thenReturn(0);

    expirationService.expireOverdue();

    verify(inventoryService, never()).release(TICKET_TYPE_ID, 2);
  }

  @Test
  void should_useBoundedBatch_when_noReservationIsOverdue() {
    when(reservationRepository.findExpiredForUpdate(NOW, 100)).thenReturn(List.of());

    expirationService.expireOverdue();

    verify(reservationRepository).findExpiredForUpdate(NOW, 100);
    verifyNoInteractions(inventoryService);
  }

  private static Reservation expiredReservation() {
    TicketType ticketType = mock(TicketType.class);
    lenient().when(ticketType.getId()).thenReturn(TICKET_TYPE_ID);
    return new Reservation(UUID.randomUUID(), ticketType, 2, NOW.minusSeconds(1));
  }
}
