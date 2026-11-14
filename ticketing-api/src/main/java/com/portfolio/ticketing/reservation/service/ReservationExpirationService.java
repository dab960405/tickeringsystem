package com.portfolio.ticketing.reservation.service;

import com.portfolio.ticketing.common.exception.BusinessRuleViolationException;
import com.portfolio.ticketing.event.service.EventLockService;
import com.portfolio.ticketing.reservation.domain.Reservation;
import com.portfolio.ticketing.reservation.domain.ReservationInventoryDisposition;
import com.portfolio.ticketing.reservation.domain.ReservationStatus;
import com.portfolio.ticketing.reservation.repository.ReservationExpirationCandidate;
import com.portfolio.ticketing.reservation.repository.ReservationRepository;
import com.portfolio.ticketing.tickettype.service.TicketTypeInventoryService;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReservationExpirationService {

  private static final int BATCH_SIZE = 100;

  private final ReservationRepository reservationRepository;
  private final TicketTypeInventoryService inventoryService;
  private final EventLockService eventLockService;
  private final Clock clock;

  public ReservationExpirationService(
      ReservationRepository reservationRepository,
      TicketTypeInventoryService inventoryService,
      EventLockService eventLockService,
      Clock clock) {
    this.reservationRepository = reservationRepository;
    this.inventoryService = inventoryService;
    this.eventLockService = eventLockService;
    this.clock = clock;
  }

  @Scheduled(
      fixedDelayString = "${ticketing.reservation.expiration-fixed-delay-ms:30000}",
      initialDelayString = "${ticketing.reservation.expiration-fixed-delay-ms:30000}")
  @Transactional
  public void expireOverdue() {
    List<ReservationExpirationCandidate> candidates =
        reservationRepository.findExpiredCandidates(clock.instant(), BATCH_SIZE);
    if (candidates.isEmpty()) {
      return;
    }

    List<UUID> eventIds =
        candidates.stream()
            .map(ReservationExpirationCandidate::getEventId)
            .distinct()
            .sorted(Comparator.comparing(UUID::toString))
            .toList();
    eventIds.forEach(eventLockService::lockShared);

    List<UUID> ticketTypeIds =
        candidates.stream()
            .map(ReservationExpirationCandidate::getTicketTypeId)
            .distinct()
            .sorted(Comparator.comparing(UUID::toString))
            .toList();
    inventoryService.lockAll(ticketTypeIds);

    List<UUID> reservationIds =
        candidates.stream()
            .map(ReservationExpirationCandidate::getId)
            .sorted(Comparator.comparing(UUID::toString))
            .toList();
    Map<UUID, UUID> candidateTicketTypes =
        candidates.stream()
            .collect(
                Collectors.toUnmodifiableMap(
                    ReservationExpirationCandidate::getId,
                    ReservationExpirationCandidate::getTicketTypeId));
    List<Reservation> lockedReservations =
        reservationRepository.findAllByIdForUpdate(reservationIds);
    Instant evaluationTime = reservationRepository.databaseNow();

    for (Reservation reservation : lockedReservations) {
      UUID candidateTicketTypeId = candidateTicketTypes.get(reservation.getId());
      if (candidateTicketTypeId == null
          || !candidateTicketTypeId.equals(reservation.getTicketType().getId())) {
        throw new BusinessRuleViolationException("Reservation inventory relationship changed");
      }
      if (reservation.getStatus() != ReservationStatus.ACTIVE
          || reservation.getInventoryDisposition() != ReservationInventoryDisposition.HELD
          || reservation.getExpiresAt().isAfter(evaluationTime)) {
        continue;
      }
      if (reservationRepository.expireActive(reservation.getId(), evaluationTime) == 1) {
        inventoryService.release(candidateTicketTypeId, reservation.getQuantity());
      }
    }
  }
}
