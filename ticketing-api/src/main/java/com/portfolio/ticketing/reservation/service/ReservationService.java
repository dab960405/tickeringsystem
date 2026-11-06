package com.portfolio.ticketing.reservation.service;

import com.portfolio.ticketing.common.exception.BusinessRuleViolationException;
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
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ReservationService {

  private final ReservationRepository reservationRepository;
  private final TicketTypeInventoryService inventoryService;
  private final ReservationProperties properties;
  private final Clock clock;

  public ReservationService(
      ReservationRepository reservationRepository,
      TicketTypeInventoryService inventoryService,
      ReservationProperties properties,
      Clock clock) {
    this.reservationRepository = reservationRepository;
    this.inventoryService = inventoryService;
    this.properties = properties;
    this.clock = clock;
  }

  @PreAuthorize("hasRole('USER')")
  public ReservationResponse create(UUID userId, ReservationRequest request) {
    TicketType ticketType = inventoryService.findRequired(request.ticketTypeId());
    Instant now = clock.instant();
    validateSale(ticketType, request.quantity(), now);
    inventoryService.reserve(ticketType.getId(), request.quantity());
    Reservation reservation =
        new Reservation(
            userId,
            ticketType,
            request.quantity(),
            now.plusSeconds(properties.getTtlMinutes() * 60));
    return ReservationResponse.from(reservationRepository.save(reservation));
  }

  @PreAuthorize("hasRole('USER')")
  public void cancel(UUID id, UUID userId) {
    Reservation reservation = findOwned(id, userId);
    if (reservation.getStatus() != ReservationStatus.ACTIVE) {
      throw new InvalidStateTransitionException(
          "Reservation cannot move from " + reservation.getStatus() + " to CANCELLED");
    }
    if (reservationRepository.cancelActive(id, userId) == 0) {
      throw new InvalidStateTransitionException("Reservation is no longer active");
    }
    inventoryService.release(reservation.getTicketType().getId(), reservation.getQuantity());
  }

  @Transactional(readOnly = true)
  public Reservation findActiveForUser(UUID id, UUID userId) {
    Reservation reservation = findOwned(id, userId);
    if (!reservation.isActiveAt(clock.instant())) {
      throw new BusinessRuleViolationException("Reservation is not active or has expired");
    }
    return reservation;
  }

  @Transactional(readOnly = true)
  public Reservation findForOrder(UUID id, UUID userId) {
    return findOwned(id, userId);
  }

  @Transactional
  public ReservationConfirmationResult confirmForPayment(UUID id, UUID userId) {
    Reservation reservation = findOwnedForUpdate(id, userId);
    if (reservation.getStatus() == ReservationStatus.CONFIRMED) {
      return ReservationConfirmationResult.CONFIRMED;
    }
    if (reservation.getStatus() != ReservationStatus.ACTIVE
        && reservation.getStatus() != ReservationStatus.EXPIRED) {
      throw new InvalidStateTransitionException(
          "Reservation cannot be confirmed from " + reservation.getStatus());
    }
    if (reservation.getStatus() == ReservationStatus.EXPIRED
        && !inventoryService.tryReserve(
            reservation.getTicketType().getId(), reservation.getQuantity())) {
      return ReservationConfirmationResult.OUT_OF_STOCK;
    }
    if (reservationRepository.confirmForPayment(id, userId) == 0) {
      throw new InvalidStateTransitionException("Reservation is no longer available for payment");
    }
    return ReservationConfirmationResult.CONFIRMED;
  }

  @Transactional
  public void releaseForPayment(UUID id, UUID userId) {
    Reservation reservation = findOwnedForUpdate(id, userId);
    if (reservation.getStatus() == ReservationStatus.CONFIRMED) {
      throw new InvalidStateTransitionException("A confirmed reservation cannot be released");
    }
    if (reservation.getStatus() != ReservationStatus.ACTIVE) {
      return;
    }
    if (reservationRepository.cancelActive(id, userId) == 1) {
      inventoryService.release(reservation.getTicketType().getId(), reservation.getQuantity());
    }
  }

  private Reservation findOwned(UUID id, UUID userId) {
    return reservationRepository
        .findByIdAndUserId(id, userId)
        .orElseThrow(() -> new ResourceNotFoundException("Reservation " + id + " not found"));
  }

  private Reservation findOwnedForUpdate(UUID id, UUID userId) {
    return reservationRepository
        .findOwnedForUpdate(id, userId)
        .orElseThrow(() -> new ResourceNotFoundException("Reservation " + id + " not found"));
  }

  private static void validateSale(TicketType ticketType, int quantity, Instant now) {
    Event event = ticketType.getEvent();
    if (event.getStatus() != EventStatus.PUBLISHED) {
      throw new BusinessRuleViolationException("Reservations require a published event");
    }
    if (!ticketType.isOnSale(now)) {
      throw new BusinessRuleViolationException("The ticket sale window is closed");
    }
    if (quantity > ticketType.getMaxPerOrder()) {
      throw new BusinessRuleViolationException(
          "Quantity cannot exceed maxPerOrder of " + ticketType.getMaxPerOrder());
    }
  }
}
