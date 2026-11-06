package com.portfolio.ticketing.reservation.service;

import com.portfolio.ticketing.reservation.domain.Reservation;
import com.portfolio.ticketing.reservation.repository.ReservationRepository;
import com.portfolio.ticketing.tickettype.service.TicketTypeInventoryService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReservationExpirationService {

  private static final int BATCH_SIZE = 100;

  private final ReservationRepository reservationRepository;
  private final TicketTypeInventoryService inventoryService;
  private final Clock clock;

  public ReservationExpirationService(
      ReservationRepository reservationRepository,
      TicketTypeInventoryService inventoryService,
      Clock clock) {
    this.reservationRepository = reservationRepository;
    this.inventoryService = inventoryService;
    this.clock = clock;
  }

  @Scheduled(
      fixedDelayString = "${ticketing.reservation.expiration-fixed-delay-ms:30000}",
      initialDelayString = "${ticketing.reservation.expiration-fixed-delay-ms:30000}")
  @Transactional
  public void expireOverdue() {
    Instant now = clock.instant();
    List<Reservation> candidates = reservationRepository.findExpiredForUpdate(now, BATCH_SIZE);
    for (Reservation reservation : candidates) {
      UUID ticketTypeId = reservation.getTicketType().getId();
      int quantity = reservation.getQuantity();
      if (reservationRepository.expireActive(reservation.getId(), now) == 1) {
        inventoryService.release(ticketTypeId, quantity);
      }
    }
  }
}
