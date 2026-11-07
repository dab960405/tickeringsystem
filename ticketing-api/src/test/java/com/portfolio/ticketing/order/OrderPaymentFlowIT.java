package com.portfolio.ticketing.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.ticketing.event.domain.Event;
import com.portfolio.ticketing.event.repository.EventRepository;
import com.portfolio.ticketing.order.api.OrderRequest;
import com.portfolio.ticketing.order.service.OrderCreationResult;
import com.portfolio.ticketing.order.service.OrderPaymentService;
import com.portfolio.ticketing.order.service.OrderService;
import com.portfolio.ticketing.payment.api.PaymentWebhookRequest;
import com.portfolio.ticketing.payment.domain.PaymentStatus;
import com.portfolio.ticketing.reservation.api.ReservationRequest;
import com.portfolio.ticketing.reservation.api.ReservationResponse;
import com.portfolio.ticketing.reservation.service.ReservationExpirationService;
import com.portfolio.ticketing.reservation.service.ReservationService;
import com.portfolio.ticketing.support.AbstractIntegrationTest;
import com.portfolio.ticketing.tickettype.domain.TicketType;
import com.portfolio.ticketing.tickettype.repository.TicketTypeRepository;
import com.portfolio.ticketing.venue.domain.Venue;
import com.portfolio.ticketing.venue.repository.VenueRepository;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = "ticketing.reservation.expiration-fixed-delay-ms=600000")
class OrderPaymentFlowIT extends AbstractIntegrationTest {

  @Autowired private ReservationService reservationService;
  @Autowired private ReservationExpirationService expirationService;
  @Autowired private OrderService orderService;
  @Autowired private OrderPaymentService orderPaymentService;
  @Autowired private TicketTypeRepository ticketTypeRepository;
  @Autowired private EventRepository eventRepository;
  @Autowired private VenueRepository venueRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void should_returnOriginalOrderAndPayment_when_idempotencyKeyIsRepeated() {
    OrderFixture fixture = createOrder(2);

    OrderCreationResult repeated =
        asUser(
            fixture.userId(),
            () ->
                orderService.create(
                    fixture.userId(),
                    fixture.idempotencyKey(),
                    new OrderRequest(fixture.reservationId())));

    IdempotencyResult result =
        new IdempotencyResult(
            repeated.created(),
            repeated.response().id().equals(fixture.orderId()),
            countRows("orders", "user_id", fixture.userId()),
            countRows("payments", "order_id", fixture.orderId()));
    assertThat(result).isEqualTo(new IdempotencyResult(false, true, 1, 1));
  }

  @Test
  void should_issueTicketsExactlyOnce_when_successfulWebhookIsRepeated() {
    OrderFixture fixture = createOrder(2);
    PaymentWebhookRequest webhook =
        new PaymentWebhookRequest(fixture.orderId(), PaymentStatus.SUCCEEDED, "provider-success");

    orderPaymentService.process(webhook);
    orderPaymentService.process(webhook);

    assertThat(paymentFlowResult(fixture))
        .isEqualTo(new PaymentFlowResult("PAID", "SUCCEEDED", "CONFIRMED", 1, 1));
  }

  @Test
  void should_releaseStockExactlyOnce_when_failedWebhookIsRepeated() {
    OrderFixture fixture = createOrder(1);
    PaymentWebhookRequest webhook =
        new PaymentWebhookRequest(fixture.orderId(), PaymentStatus.FAILED, null);

    orderPaymentService.process(webhook);
    orderPaymentService.process(webhook);

    assertThat(paymentFlowResult(fixture))
        .isEqualTo(new PaymentFlowResult("CANCELLED", "FAILED", "CANCELLED", 0, 1));
  }

  @Test
  void should_refundWithoutTickets_when_expiredReservationHasNoReplacementStock() {
    OrderFixture fixture = createOrder(1);
    jdbcTemplate.update(
        "UPDATE reservations SET expires_at = ? WHERE id = ?",
        Timestamp.from(Instant.now().minusSeconds(60)),
        fixture.reservationId());
    expirationService.expireOverdue();
    UUID otherUserId = createUser();
    asUser(
        otherUserId,
        () ->
            reservationService.create(
                otherUserId, new ReservationRequest(fixture.ticketTypeId(), 1)));

    orderPaymentService.process(
        new PaymentWebhookRequest(fixture.orderId(), PaymentStatus.SUCCEEDED, "provider-late"));

    assertThat(paymentFlowResult(fixture))
        .isEqualTo(new PaymentFlowResult("REFUNDED", "REFUNDED", "EXPIRED", 0, 0));
  }

  private OrderFixture createOrder(int stock) {
    Instant now = Instant.now();
    UUID userId = createUser();
    Venue venue = venueRepository.save(new Venue("Arena", "Lima", "Main St 1", 500));
    Event event =
        new Event(
            userId,
            venue,
            "Payment Flow Show",
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
    String idempotencyKey = "checkout-" + UUID.randomUUID();
    OrderCreationResult order =
        asUser(
            userId,
            () -> orderService.create(userId, idempotencyKey, new OrderRequest(reservation.id())));
    return new OrderFixture(
        userId, ticketType.getId(), reservation.id(), order.response().id(), idempotencyKey);
  }

  private UUID createUser() {
    return jdbcTemplate.queryForObject(
        "INSERT INTO users (email, password_hash, full_name, role)"
            + " VALUES (?, 'hash', 'Payment Buyer', 'USER') RETURNING id",
        UUID.class,
        "payment-" + UUID.randomUUID() + "@example.com");
  }

  private <T> T asUser(UUID userId, Supplier<T> action) {
    SecurityContext context = new SecurityContextImpl();
    context.setAuthentication(
        new UsernamePasswordAuthenticationToken(
            userId.toString(), null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    SecurityContextHolder.setContext(context);
    try {
      return action.get();
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  private PaymentFlowResult paymentFlowResult(OrderFixture fixture) {
    return new PaymentFlowResult(
        queryString("SELECT status FROM orders WHERE id = ?", fixture.orderId()),
        queryString("SELECT status FROM payments WHERE order_id = ?", fixture.orderId()),
        queryString("SELECT status FROM reservations WHERE id = ?", fixture.reservationId()),
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM tickets t JOIN order_items oi ON t.order_item_id = oi.id"
                + " WHERE oi.order_id = ?",
            Integer.class,
            fixture.orderId()),
        jdbcTemplate.queryForObject(
            "SELECT available_quantity FROM ticket_types WHERE id = ?",
            Integer.class,
            fixture.ticketTypeId()));
  }

  private String queryString(String sql, UUID id) {
    return jdbcTemplate.queryForObject(sql, String.class, id);
  }

  private int countRows(String table, String column, UUID id) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?", Integer.class, id);
  }

  private record OrderFixture(
      UUID userId, UUID ticketTypeId, UUID reservationId, UUID orderId, String idempotencyKey) {}

  private record IdempotencyResult(
      boolean created, boolean sameOrder, int orderCount, int paymentCount) {}

  private record PaymentFlowResult(
      String orderStatus,
      String paymentStatus,
      String reservationStatus,
      int ticketCount,
      int availableQuantity) {}
}
