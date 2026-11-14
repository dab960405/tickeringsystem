package com.portfolio.ticketing.reservation.service;

import com.portfolio.ticketing.common.exception.BusinessRuleViolationException;
import com.portfolio.ticketing.common.exception.InvalidStateTransitionException;
import com.portfolio.ticketing.common.exception.ResourceNotFoundException;
import com.portfolio.ticketing.event.domain.EventStatus;
import com.portfolio.ticketing.event.service.EventLockService;
import com.portfolio.ticketing.reservation.api.ReservationRequest;
import com.portfolio.ticketing.reservation.api.ReservationResponse;
import com.portfolio.ticketing.reservation.config.ReservationProperties;
import com.portfolio.ticketing.reservation.domain.Reservation;
import com.portfolio.ticketing.reservation.domain.ReservationInventoryDisposition;
import com.portfolio.ticketing.reservation.domain.ReservationStatus;
import com.portfolio.ticketing.reservation.repository.ReservationRepository;
import com.portfolio.ticketing.tickettype.domain.TicketType;
import com.portfolio.ticketing.tickettype.service.TicketTypeInventoryService;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ReservationService {

  private final ReservationRepository reservationRepository;
  private final TicketTypeInventoryService inventoryService;
  private final EventLockService eventLockService;
  private final ReservationProperties properties;
  private final Clock clock;

  public ReservationService(
      ReservationRepository reservationRepository,
      TicketTypeInventoryService inventoryService,
      EventLockService eventLockService,
      ReservationProperties properties,
      Clock clock) {
    this.reservationRepository = reservationRepository;
    this.inventoryService = inventoryService;
    this.eventLockService = eventLockService;
    this.properties = properties;
    this.clock = clock;
  }

  @PreAuthorize("hasRole('USER')")
  public ReservationResponse create(UUID userId, ReservationRequest request) {
    UUID eventId = inventoryService.findEventId(request.ticketTypeId());
    EventStatus lockedEventStatus = eventLockService.lockShared(eventId);
    TicketType ticketType = inventoryService.lockRequired(request.ticketTypeId());
    Instant now = clock.instant();
    validateSale(lockedEventStatus, ticketType, request.quantity(), now);
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
    UUID ticketTypeId = discoverTicketTypeId(id, userId);
    inventoryService.lockRequired(ticketTypeId);
    Reservation reservation = findOwnedForUpdate(id, userId);
    requireSameTicketType(reservation, ticketTypeId);
    if (reservation.getStatus() != ReservationStatus.ACTIVE
        || reservation.getInventoryDisposition() != ReservationInventoryDisposition.HELD) {
      throw new InvalidStateTransitionException(
          "Reservation cannot move from " + reservation.getStatus() + " to CANCELLED");
    }
    if (reservationRepository.cancelActive(id, userId) == 0) {
      throw new InvalidStateTransitionException("Reservation is no longer active");
    }
    inventoryService.release(ticketTypeId, reservation.getQuantity());
  }

  /**
   * Locks E FOR SHARE → R FOR UPDATE and revalidates both, so the caller can attach a new order (O)
   * without racing a cancellation, expiration or release that resolves the reservation. T is
   * skipped because order creation does not touch inventory.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Reservation lockActiveForOrder(UUID id, UUID userId) {
    UUID ticketTypeId = discoverTicketTypeId(id, userId);
    EventStatus lockedEventStatus =
        eventLockService.lockShared(inventoryService.findEventId(ticketTypeId));
    Reservation reservation = findOwnedForUpdate(id, userId);
    requireSameTicketType(reservation, ticketTypeId);
    if (lockedEventStatus != EventStatus.PUBLISHED) {
      throw new BusinessRuleViolationException("Orders require a published event");
    }
    if (reservation.getInventoryDisposition() != ReservationInventoryDisposition.HELD
        || !reservation.isActiveAt(clock.instant())) {
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
    UUID ticketTypeId = discoverTicketTypeId(id, userId);
    EventStatus lockedEventStatus =
        eventLockService.lockShared(inventoryService.findEventId(ticketTypeId));
    inventoryService.lockRequired(ticketTypeId);
    LockedReservation reservation =
        LockedReservation.of(findOwnedForUpdate(id, userId), ticketTypeId);
    Instant evaluationTime = reservationRepository.databaseNow();

    if (reservation.status() == ReservationStatus.CONFIRMED
        && reservation.disposition() == ReservationInventoryDisposition.CONSUMED) {
      return ReservationConfirmationResult.CONFIRMED;
    }
    if (lockedEventStatus != EventStatus.PUBLISHED) {
      releaseHeldForUnpublishedEvent(id, userId, reservation);
      return ReservationConfirmationResult.RECOVERY_NOT_ALLOWED;
    }
    if (reservation.status() == ReservationStatus.ACTIVE
        && reservation.disposition() == ReservationInventoryDisposition.HELD
        && reservation.expiresAt().isAfter(evaluationTime)) {
      confirmActive(id, userId, evaluationTime);
      return ReservationConfirmationResult.CONFIRMED;
    }
    if (reservation.status() == ReservationStatus.CANCELLED) {
      return ReservationConfirmationResult.RECOVERY_NOT_ALLOWED;
    }
    boolean requiresRecovery =
        reservation.status() == ReservationStatus.EXPIRED
            && reservation.disposition() == ReservationInventoryDisposition.RELEASED;
    if (reservation.status() == ReservationStatus.ACTIVE
        && reservation.disposition() == ReservationInventoryDisposition.HELD) {
      expireActiveReservation(reservation, evaluationTime);
      requiresRecovery = true;
    }
    if (!requiresRecovery) {
      throw new InvalidStateTransitionException(
          "Reservation cannot be confirmed from " + reservation.status());
    }
    if (!canRecover(reservation, evaluationTime)) {
      return ReservationConfirmationResult.RECOVERY_NOT_ALLOWED;
    }
    if (!inventoryService.tryReserve(ticketTypeId, reservation.quantity())) {
      return ReservationConfirmationResult.OUT_OF_STOCK;
    }
    if (reservationRepository.confirmRecovered(id, userId) == 0) {
      throw new InvalidStateTransitionException("Reservation is no longer available for payment");
    }
    return ReservationConfirmationResult.CONFIRMED;
  }

  @Transactional
  public ReservationReleaseResult releaseForPayment(UUID id, UUID userId) {
    UUID ticketTypeId = discoverTicketTypeId(id, userId);
    eventLockService.lockShared(inventoryService.findEventId(ticketTypeId));
    inventoryService.lockRequired(ticketTypeId);
    Reservation reservation = findOwnedForUpdate(id, userId);
    requireSameTicketType(reservation, ticketTypeId);
    if (reservation.getStatus() == ReservationStatus.CONFIRMED) {
      return ReservationReleaseResult.CONFIRMED;
    }
    if (reservation.getStatus() != ReservationStatus.ACTIVE) {
      return ReservationReleaseResult.ALREADY_RELEASED;
    }
    if (reservation.getInventoryDisposition() != ReservationInventoryDisposition.HELD) {
      throw new InvalidStateTransitionException("Active reservation does not hold inventory");
    }
    if (reservationRepository.cancelActive(id, userId) != 1) {
      throw new InvalidStateTransitionException("Active reservation could not be released");
    }
    inventoryService.release(ticketTypeId, reservation.getQuantity());
    return ReservationReleaseResult.RELEASED;
  }

  private boolean canRecover(LockedReservation reservation, Instant evaluationTime) {
    Instant recoveryDeadline = reservation.expiresAt().plus(properties.getRecoveryGrace());
    return !evaluationTime.isAfter(recoveryDeadline)
        && reservation.eventStartsAt().isAfter(evaluationTime);
  }

  private void confirmActive(UUID id, UUID userId, Instant evaluationTime) {
    if (reservationRepository.confirmActive(id, userId, evaluationTime) == 0) {
      throw new InvalidStateTransitionException("Reservation is no longer active for payment");
    }
  }

  /**
   * A payment that arrives after the event stopped selling must not consume stock: an active hold
   * is cancelled and its inventory returned exactly once; resolved reservations stay untouched.
   */
  private void releaseHeldForUnpublishedEvent(UUID id, UUID userId, LockedReservation reservation) {
    if (reservation.status() != ReservationStatus.ACTIVE
        || reservation.disposition() != ReservationInventoryDisposition.HELD) {
      return;
    }
    if (reservationRepository.cancelActive(id, userId) != 1) {
      throw new InvalidStateTransitionException("Reservation could not be released for event");
    }
    inventoryService.release(reservation.ticketTypeId(), reservation.quantity());
  }

  private void expireActiveReservation(LockedReservation reservation, Instant evaluationTime) {
    if (reservationRepository.expireActive(reservation.id(), evaluationTime) != 1) {
      throw new InvalidStateTransitionException("Reservation could not be expired for recovery");
    }
    inventoryService.release(reservation.ticketTypeId(), reservation.quantity());
  }

  /**
   * Values captured right after the E → T → R locks. Modifying queries clear the persistence
   * context, so later decisions must not navigate lazy associations of the locked entity.
   */
  private record LockedReservation(
      UUID id,
      UUID ticketTypeId,
      ReservationStatus status,
      ReservationInventoryDisposition disposition,
      int quantity,
      Instant expiresAt,
      Instant eventStartsAt) {

    static LockedReservation of(Reservation reservation, UUID expectedTicketTypeId) {
      requireSameTicketType(reservation, expectedTicketTypeId);
      return new LockedReservation(
          reservation.getId(),
          expectedTicketTypeId,
          reservation.getStatus(),
          reservation.getInventoryDisposition(),
          reservation.getQuantity(),
          reservation.getExpiresAt(),
          reservation.getTicketType().getEvent().getStartsAt());
    }
  }

  private static void requireSameTicketType(Reservation reservation, UUID expectedTicketTypeId) {
    if (!reservation.getTicketType().getId().equals(expectedTicketTypeId)) {
      throw new BusinessRuleViolationException("Reservation inventory relationship changed");
    }
  }

  private UUID discoverTicketTypeId(UUID id, UUID userId) {
    return reservationRepository
        .findTicketTypeIdByIdAndUserId(id, userId)
        .orElseThrow(() -> new ResourceNotFoundException("Reservation " + id + " not found"));
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

  private static void validateSale(
      EventStatus lockedEventStatus, TicketType ticketType, int quantity, Instant now) {
    if (lockedEventStatus != EventStatus.PUBLISHED) {
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
