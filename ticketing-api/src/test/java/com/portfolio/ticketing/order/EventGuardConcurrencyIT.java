package com.portfolio.ticketing.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portfolio.ticketing.common.exception.BusinessRuleViolationException;
import com.portfolio.ticketing.event.domain.Event;
import com.portfolio.ticketing.event.repository.EventRepository;
import com.portfolio.ticketing.event.service.EventCancellationService;
import com.portfolio.ticketing.order.api.OrderRequest;
import com.portfolio.ticketing.order.service.OrderCreationResult;
import com.portfolio.ticketing.order.service.OrderPaymentService;
import com.portfolio.ticketing.order.service.OrderService;
import com.portfolio.ticketing.payment.api.PaymentWebhookRequest;
import com.portfolio.ticketing.payment.domain.PaymentStatus;
import com.portfolio.ticketing.payment.domain.ProviderOperationKind;
import com.portfolio.ticketing.reservation.api.ReservationRequest;
import com.portfolio.ticketing.reservation.api.ReservationResponse;
import com.portfolio.ticketing.reservation.service.ReservationConfirmationResult;
import com.portfolio.ticketing.reservation.service.ReservationExpirationService;
import com.portfolio.ticketing.reservation.service.ReservationService;
import com.portfolio.ticketing.support.AbstractIntegrationTest;
import com.portfolio.ticketing.tickettype.domain.TicketType;
import com.portfolio.ticketing.tickettype.repository.TicketTypeRepository;
import com.portfolio.ticketing.venue.domain.Venue;
import com.portfolio.ticketing.venue.repository.VenueRepository;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;

/**
 * Proves on real PostgreSQL that confirmation, late-payment recovery and order creation wait on the
 * event lock (E FOR SHARE) and honour a cancellation that commits while they wait.
 */
@SpringBootTest
@TestPropertySource(properties = "ticketing.reservation.expiration-fixed-delay-ms=600000")
class EventGuardConcurrencyIT extends AbstractIntegrationTest {

  @Autowired private ReservationService reservationService;
  @Autowired private EventCancellationService eventCancellationService;
  @Autowired private ReservationExpirationService expirationService;
  @Autowired private OrderService orderService;
  @Autowired private OrderPaymentService orderPaymentService;
  @Autowired private EventRepository eventRepository;
  @Autowired private TicketTypeRepository ticketTypeRepository;
  @Autowired private VenueRepository venueRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private DataSource dataSource;

  @AfterEach
  void clearSecurity() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void should_releaseHeldStockWithoutTickets_when_cancellationCommitsWhileCaptureWaits()
      throws Exception {
    OrderFixture fixture = createOrder(createReservation(2));

    cancelEventWhile(
        fixture.reservation().eventId(), () -> orderPaymentService.process(capture(fixture)));

    assertThat(outcome(fixture))
        .isEqualTo(new Outcome("PAID", "SUCCEEDED", "CANCELLED", "UNFULFILLABLE", 0, 2));
  }

  @Test
  void should_notRecoverExpiredReservation_when_cancellationCommitsWhileRecoveryWaits()
      throws Exception {
    OrderFixture fixture = createOrder(createReservation(2));
    jdbcTemplate.update(
        "UPDATE reservations SET expires_at = ? WHERE id = ?",
        Timestamp.from(Instant.now().minusSeconds(60)),
        fixture.reservation().reservationId());
    expirationService.expireOverdue();
    assertThat(availableQuantity(fixture.reservation().ticketTypeId())).isEqualTo(2);

    cancelEventWhile(
        fixture.reservation().eventId(), () -> orderPaymentService.process(capture(fixture)));

    assertThat(outcome(fixture))
        .isEqualTo(new Outcome("PAID", "SUCCEEDED", "EXPIRED", "UNFULFILLABLE", 0, 2));
  }

  @Test
  void should_notResurrectCancelledOrder_when_captureWaitsForCancellationCommit() throws Exception {
    OrderFixture fixture = createOrder(createReservation(2));

    try (Connection canceller = dataSource.getConnection();
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      canceller.setAutoCommit(false);
      try (PreparedStatement lock =
          canceller.prepareStatement("SELECT status FROM events WHERE id = ? FOR UPDATE")) {
        lock.setObject(1, fixture.reservation().eventId());
        lock.executeQuery().close();
      }

      CompletableFuture<Void> capture =
          CompletableFuture.runAsync(() -> orderPaymentService.process(capture(fixture)), executor);
      awaitWaitingOnEventRow(canceller, fixture.reservation().eventId(), capture);

      try (PreparedStatement cancelEvent =
          canceller.prepareStatement("UPDATE events SET status = 'CANCELLED' WHERE id = ?");
          PreparedStatement expireReservation =
              canceller.prepareStatement(
                  "UPDATE reservations SET status = 'EXPIRED', inventory_disposition = 'RELEASED'"
                      + " WHERE id = ?");
          PreparedStatement restoreStock =
              canceller.prepareStatement(
                  "UPDATE ticket_types SET available_quantity = available_quantity + 1 WHERE id = ?");
          PreparedStatement cancelOrder =
              canceller.prepareStatement("UPDATE orders SET status = 'CANCELLED' WHERE id = ?")) {
        cancelEvent.setObject(1, fixture.reservation().eventId());
        assertThat(cancelEvent.executeUpdate()).isEqualTo(1);
        restoreStock.setObject(1, fixture.reservation().ticketTypeId());
        assertThat(restoreStock.executeUpdate()).isEqualTo(1);
        expireReservation.setObject(1, fixture.reservation().reservationId());
        assertThat(expireReservation.executeUpdate()).isEqualTo(1);
        cancelOrder.setObject(1, fixture.orderId());
        assertThat(cancelOrder.executeUpdate()).isEqualTo(1);
      }
      canceller.commit();
      capture.get(10, TimeUnit.SECONDS);
    }

    assertThat(outcome(fixture))
        .isEqualTo(new Outcome("CANCELLED", "SUCCEEDED", "EXPIRED", "UNFULFILLABLE", 0, 2));
  }

  @Test
  void should_rejectOrder_when_cancellationCommitsWhileCreationWaits() throws Exception {
    ReservationFixture reservation = createReservation(2);

    assertThatThrownBy(() ->
            cancelEventWhile(
                reservation.eventId(),
                () ->
                    asUser(
                        reservation.userId(),
                        () ->
                            orderService.create(
                                reservation.userId(),
                                "checkout-" + UUID.randomUUID(),
                                new OrderRequest(reservation.reservationId())))))
        .hasRootCauseInstanceOf(BusinessRuleViolationException.class);

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE reservation_id = ?",
                Integer.class,
                reservation.reservationId()))
        .isZero();
  }

  @Test
  void should_expireActiveReservationsAndCancelPendingOrders_when_eventIsCancelled() {
    OrderFixture fixture = createOrder(createReservation(2));

    eventCancellationService.cancel(fixture.reservation().eventId());

    assertThat(outcome(fixture))
        .isEqualTo(new Outcome("CANCELLED", "PENDING", "EXPIRED", "PENDING", 0, 2));

    eventCancellationService.cancel(fixture.reservation().eventId());

    assertThat(outcome(fixture))
        .isEqualTo(new Outcome("CANCELLED", "PENDING", "EXPIRED", "PENDING", 0, 2));
  }

  @Test
  void should_cancelPendingOrder_when_itsReservationExpiredBeforeEventCancellation() {
    OrderFixture fixture = createOrder(createReservation(2));
    jdbcTemplate.update(
        "UPDATE reservations SET expires_at = ? WHERE id = ?",
        Timestamp.from(Instant.now().minusSeconds(60)),
        fixture.reservation().reservationId());
    expirationService.expireOverdue();

    assertThat(outcome(fixture))
        .isEqualTo(new Outcome("PENDING_PAYMENT", "PENDING", "EXPIRED", "PENDING", 0, 2));

    eventCancellationService.cancel(fixture.reservation().eventId());

    assertThat(outcome(fixture))
        .isEqualTo(new Outcome("CANCELLED", "PENDING", "EXPIRED", "PENDING", 0, 2));
  }

  @Test
  void should_applyConcurrentLateCaptureOnlyOnce_when_eventWasAlreadyCancelled() throws Exception {
    OrderFixture fixture = createOrder(createReservation(2));
    eventCancellationService.cancel(fixture.reservation().eventId());
    PaymentWebhookRequest request = capture(fixture);

    // Close the blocker connection before the executor: otherwise executor.close() can
    // wait forever for tasks that are still blocked by this connection.
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        Connection blocker = dataSource.getConnection()) {
      blocker.setAutoCommit(false);
      int blockerPid;
      try (PreparedStatement lock =
          blocker.prepareStatement("SELECT id FROM orders WHERE id = ? FOR UPDATE")) {
        lock.setObject(1, fixture.orderId());
        lock.executeQuery().close();
      }
      try (PreparedStatement pid = blocker.prepareStatement("SELECT pg_backend_pid()");
          var result = pid.executeQuery()) {
        result.next();
        blockerPid = result.getInt(1);
      }

      CompletableFuture<Void> first =
          CompletableFuture.runAsync(() -> orderPaymentService.process(request), executor);
      CompletableFuture<Void> second =
          CompletableFuture.runAsync(() -> orderPaymentService.process(request), executor);
      Awaitility.await()
          .atMost(Duration.ofSeconds(10))
          .pollInterval(Duration.ofMillis(25))
          .untilAsserted(
              () -> {
                Integer waiting =
                    jdbcTemplate.queryForObject(
                        """
                        SELECT COUNT(*) FROM pg_stat_activity
                        WHERE wait_event_type = 'Lock'
                          AND ? = ANY(pg_blocking_pids(pid))
                          AND query ILIKE '%FROM orders WHERE id%FOR UPDATE%'
                        """,
                        Integer.class,
                        blockerPid);
                // Lock order is E → T → R → O: one request can wait on O while
                // the other is still serialized behind the first request's R lock.
                assertThat(waiting).isNotNull().isGreaterThanOrEqualTo(1);
              });
      blocker.commit();
      CompletableFuture.allOf(first, second).get(10, TimeUnit.SECONDS);
    }

    assertThat(outcome(fixture))
        .isEqualTo(new Outcome("CANCELLED", "SUCCEEDED", "EXPIRED", "UNFULFILLABLE", 0, 2));
  }

  @Test
  void should_preservePaidOrdersAndConfirmedReservations_when_eventIsCancelled() {
    OrderFixture fixture = createOrder(createReservation(2));
    orderPaymentService.process(capture(fixture));

    eventCancellationService.cancel(fixture.reservation().eventId());

    assertThat(outcome(fixture))
        .isEqualTo(new Outcome("PAID", "SUCCEEDED", "CONFIRMED", "FULFILLED", 1, 1));
  }

  @Test
  void should_keepConfirmedPurchase_when_eventIsCancelledAfterConfirmation() {
    OrderFixture fixture = createOrder(createReservation(2));
    orderPaymentService.process(capture(fixture));
    jdbcTemplate.update(
        "UPDATE events SET status = 'CANCELLED' WHERE id = ?", fixture.reservation().eventId());

    ReservationConfirmationResult redelivered =
        reservationService.confirmForPayment(
            fixture.reservation().reservationId(), fixture.reservation().userId());

    assertThat(redelivered).isEqualTo(ReservationConfirmationResult.CONFIRMED);
    assertThat(outcome(fixture))
        .isEqualTo(new Outcome("PAID", "SUCCEEDED", "CONFIRMED", "FULFILLED", 1, 1));
  }

  /**
   * Holds E FOR UPDATE on a separate connection, proves the operation blocks on E FOR SHARE, then
   * commits the cancellation and returns the operation's outcome.
   */
  private void cancelEventWhile(UUID eventId, Runnable operation) throws Exception {
    try (Connection canceller = dataSource.getConnection();
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      canceller.setAutoCommit(false);
      try (PreparedStatement lock =
          canceller.prepareStatement("SELECT status FROM events WHERE id = ? FOR UPDATE")) {
        lock.setObject(1, eventId);
        lock.executeQuery().close();
      }

      CompletableFuture<Void> pending = CompletableFuture.runAsync(operation, executor);
      try {
        awaitWaitingOnEventRow(canceller, eventId, pending);
      } catch (AssertionError notBlocked) {
        canceller.rollback();
        throw notBlocked;
      }

      try (PreparedStatement cancel =
          canceller.prepareStatement("UPDATE events SET status = 'CANCELLED' WHERE id = ?")) {
        cancel.setObject(1, eventId);
        assertThat(cancel.executeUpdate()).isEqualTo(1);
      }
      canceller.commit();
      pending.get(10, TimeUnit.SECONDS);
    }
  }

  private void awaitWaitingOnEventRow(
      Connection blocker, UUID eventId, CompletableFuture<Void> pending) throws Exception {
    int blockerPid;
    try (var statement = blocker.createStatement();
        var result = statement.executeQuery("SELECT pg_backend_pid()")) {
      result.next();
      blockerPid = result.getInt(1);
    }

    Awaitility.await()
        .alias("operation waits for event row " + eventId)
        .atMost(Duration.ofSeconds(10))
        .pollInterval(Duration.ofMillis(25))
        .until(() -> {
          Integer waiting =
              jdbcTemplate.queryForObject(
                  """
                  SELECT COUNT(*) FROM pg_stat_activity
                  WHERE wait_event_type = 'Lock'
                    AND ? = ANY(pg_blocking_pids(pid))
                    AND query ILIKE '%FROM events WHERE id%FOR SHARE%'
                  """,
                  Integer.class, blockerPid);
          return waiting != null && waiting > 0;
        });
    assertThat(pending).as("operation must still wait on E").isNotDone();
  }

  private ReservationFixture createReservation(int stock) {
    Instant now = Instant.now();
    UUID userId =
        jdbcTemplate.queryForObject(
            "INSERT INTO users (email, password_hash, full_name, role)"
                + " VALUES (?, 'hash', 'Guard Buyer', 'USER') RETURNING id",
            UUID.class,
            "event-guard-" + UUID.randomUUID() + "@example.com");
    Venue venue = venueRepository.save(new Venue("Arena", "Lima", "Main St 1", 500));
    Event event =
        new Event(
            userId,
            venue,
            "Event Guard Show",
            null,
            "MUSIC",
            now.plusSeconds(7200),
            now.plusSeconds(10800));
    event.publish();
    eventRepository.saveAndFlush(event);
    TicketType ticketType =
        ticketTypeRepository.saveAndFlush(
            new TicketType(
                event,
                "General",
                new BigDecimal("40.00"),
                stock,
                now.minusSeconds(120),
                now.plusSeconds(3600),
                4));
    ReservationResponse reservation =
        asUser(
            userId,
            () -> reservationService.create(userId, new ReservationRequest(ticketType.getId(), 1)));
    return new ReservationFixture(userId, event.getId(), ticketType.getId(), reservation.id());
  }

  private OrderFixture createOrder(ReservationFixture reservation) {
    OrderCreationResult order =
        asUser(
            reservation.userId(),
            () ->
                orderService.create(
                    reservation.userId(),
                    "checkout-" + UUID.randomUUID(),
                    new OrderRequest(reservation.reservationId())));
    return new OrderFixture(reservation, order.response().id());
  }

  private static PaymentWebhookRequest capture(OrderFixture fixture) {
    return new PaymentWebhookRequest(
        fixture.orderId(),
        PaymentStatus.SUCCEEDED,
        "provider-" + fixture.orderId(),
        UUID.randomUUID(),
        "operation-" + UUID.randomUUID(),
        ProviderOperationKind.PAYMENT_CAPTURE,
        new BigDecimal("40.00"),
        "USD");
  }

  private <T> T asUser(UUID userId, Supplier<T> action) {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                userId.toString(), null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    try {
      return action.get();
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  private Outcome outcome(OrderFixture fixture) {
    UUID orderId = fixture.orderId();
    return new Outcome(
        jdbcTemplate.queryForObject(
            "SELECT status FROM orders WHERE id = ?", String.class, orderId),
        jdbcTemplate.queryForObject(
            "SELECT status FROM payments WHERE order_id = ?", String.class, orderId),
        jdbcTemplate.queryForObject(
            "SELECT status FROM reservations WHERE id = ?",
            String.class,
            fixture.reservation().reservationId()),
        jdbcTemplate.queryForObject(
            "SELECT fulfillment_status FROM orders WHERE id = ?", String.class, orderId),
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM tickets t JOIN order_items oi ON t.order_item_id = oi.id"
                + " WHERE oi.order_id = ?",
            Integer.class,
            orderId),
        availableQuantity(fixture.reservation().ticketTypeId()));
  }

  private int availableQuantity(UUID ticketTypeId) {
    return jdbcTemplate.queryForObject(
        "SELECT available_quantity FROM ticket_types WHERE id = ?", Integer.class, ticketTypeId);
  }

  private record ReservationFixture(
      UUID userId, UUID eventId, UUID ticketTypeId, UUID reservationId) {}

  private record OrderFixture(ReservationFixture reservation, UUID orderId) {}

  private record Outcome(
      String orderStatus,
      String paymentStatus,
      String reservationStatus,
      String fulfillmentStatus,
      int ticketCount,
      int availableQuantity) {}
}
