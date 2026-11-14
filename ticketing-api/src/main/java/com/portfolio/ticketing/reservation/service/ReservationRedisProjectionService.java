package com.portfolio.ticketing.reservation.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.ticketing.reservation.domain.ReservationStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class ReservationRedisProjectionService {

  public static final String KEY_PREFIX = "ticketing:reservation:";

  private static final Logger log =
      LoggerFactory.getLogger(ReservationRedisProjectionService.class);

  private final StringRedisTemplate redisTemplate;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  public ReservationRedisProjectionService(
      StringRedisTemplate redisTemplate, ObjectMapper objectMapper, Clock clock) {
    this.redisTemplate = redisTemplate;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  public void writeActiveAfterCommit(UUID reservationId, Instant expiresAt) {
    afterCommit(() -> writeActive(reservationId, expiresAt));
  }

  public void deleteAfterCommit(UUID reservationId) {
    afterCommit(() -> delete(reservationId));
  }

  private void writeActive(UUID reservationId, Instant expiresAt) {
    Duration ttl = Duration.between(clock.instant(), expiresAt);
    if (ttl.isNegative() || ttl.isZero()) {
      delete(reservationId);
      return;
    }
    try {
      String payload =
          objectMapper.writeValueAsString(
              new ReservationProjection(ReservationStatus.ACTIVE.name(), expiresAt));
      redisTemplate.opsForValue().set(key(reservationId), payload, ttl);
    } catch (JsonProcessingException | DataAccessException exception) {
      log.warn(
          "Reservation Redis projection write failed for {}; continuing with PostgreSQL",
          reservationId,
          exception);
    }
  }

  private void delete(UUID reservationId) {
    try {
      redisTemplate.delete(key(reservationId));
    } catch (DataAccessException exception) {
      log.warn(
          "Reservation Redis projection delete failed for {}; continuing with PostgreSQL",
          reservationId,
          exception);
    }
  }

  private static void afterCommit(Runnable action) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      action.run();
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            action.run();
          }
        });
  }

  private static String key(UUID reservationId) {
    return KEY_PREFIX + reservationId;
  }

  private record ReservationProjection(String status, Instant expiresAt) {}
}
