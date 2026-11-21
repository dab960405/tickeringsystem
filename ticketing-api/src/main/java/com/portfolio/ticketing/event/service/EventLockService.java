package com.portfolio.ticketing.event.service;

import com.portfolio.ticketing.common.exception.ResourceNotFoundException;
import com.portfolio.ticketing.event.domain.EventStatus;
import com.portfolio.ticketing.event.repository.EventRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Acquires the event row lock (E) of ADR-016 and returns the status read under that lock.
 *
 * <p>MANDATORY propagation guarantees the lock lives in the caller's transaction, so it is held
 * until the protected operation commits instead of being released by an inner transaction.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class EventLockService {

  private final EventRepository eventRepository;

  public EventLockService(EventRepository eventRepository) {
    this.eventRepository = eventRepository;
  }

  /** {@code FOR SHARE}: concurrent sales proceed together, cancellation waits for them. */
  public EventStatus lockShared(UUID eventId) {
    return eventRepository
        .findStatusForShare(eventId)
        .map(EventStatus::valueOf)
        .orElseThrow(() -> notFound(eventId));
  }

  /** {@code FOR UPDATE}: excludes every sale that holds the shared lock on the same event. */
  public EventStatus lockExclusive(UUID eventId) {
    return eventRepository
        .findStatusForUpdate(eventId)
        .map(EventStatus::valueOf)
        .orElseThrow(() -> notFound(eventId));
  }

  private static ResourceNotFoundException notFound(UUID eventId) {
    return new ResourceNotFoundException("Event " + eventId + " not found");
  }
}
