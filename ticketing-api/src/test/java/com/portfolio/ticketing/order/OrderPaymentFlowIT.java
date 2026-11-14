package com.portfolio.ticketing.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.ticketing.common.exception.DuplicateResourceException;
import com.portfolio.ticketing.event.domain.Event;
import com.portfolio.ticketing.event.repository.EventRepository;
import com.portfolio.ticketing.order.api.OrderRequest;
import com.portfolio.ticketing.order.service.OrderCancellationService;
import com.portfolio.ticketing.order.service.OrderCreationResult;
import com.portfolio.ticketing.order.repository.OrderExpirationCandidate;
import com.portfolio.ticketing.order.repository.OrderRepository;
import com.portfolio.ticketing.order.service.OrderExpirationProcessor;
import com.portfolio.ticketing.order.service.OrderExpirationService;
import com.portfolio.ticketing.order.service.OrderPaymentService;
import com.portfolio.ticketing.order.service.OrderService;
import com.portfolio.ticketing.payment.api.PaymentWebhookRequest;
import com.portfolio.ticketing.payment.config.PaymentProperties;
import com.portfolio.ticketing.payment.domain.PaymentStatus;
import com.portfolio.ticketing.payment.service.PaymentWebhookService;
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
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(
    properties = {
      "ticketing.reservation.expiration-fixed-delay-ms=600000",
      "ticketing.order.expiration-fixed-delay-ms=600000"
    })
class OrderPaymentFlowIT extends AbstractIntegrationTest {

  @Autowired private ReservationService reservationService;
  @Autowired private ReservationExpirationService expirationService;
  @Autowired private OrderService orderService;
  @Autowired private OrderPaymentService orderPaymentService;
  @Autowired private OrderExpirationService orderExpirationService;
  @Autowired private OrderExpirationProcessor orderExpirationProcessor;
  @Autowired private OrderRepository orderRepository;
  @Autowired private PaymentWebhookService paymentWebhookService;
  @Autowired private OrderCancellationService orderCancellationService;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private PaymentProperties paymentProperties;
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
    PaymentWebhookRequest webhook = webhook(fixture, PaymentStatus.SUCCEEDED, "provider-success");

    orderPaymentService.process(webhook);
    orderPaymentService.process(webhook);

    assertThat(paymentFlowResult(fixture))
        .isEqualTo(new PaymentFlowResult("PAID", "SUCCEEDED", "CONFIRMED", 1, 1));
  }

  @Test
  void should_releaseStockExactlyOnce_when_failedWebhookIsRepeated() {
    OrderFixture fixture = createOrder(1);
    PaymentWebhookRequest webhook = webhook(fixture, PaymentStatus.FAILED, null);

    orderPaymentService.process(webhook);
    orderPaymentService.process(webhook);

    assertThat(paymentFlowResult(fixture))
        .isEqualTo(new PaymentFlowResult("CANCELLED", "FAILED", "CANCELLED", 0, 1));
  }

  @Test
  void should_requireRefundWithoutTicketsOrRefundEvidence_when_expiredStockIsUnavailable() {
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

    orderPaymentService.process(webhook(fixture, PaymentStatus.SUCCEEDED, "provider-late"));

    assertThat(paymentFlowResult(fixture))
        .isEqualTo(new PaymentFlowResult("PAID", "SUCCEEDED", "EXPIRED", 0, 0));
    assertThat(queryString("SELECT fulfillment_status FROM orders WHERE id = ?", fixture.orderId()))
        .isEqualTo("UNFULFILLABLE");
    assertThat(queryString("SELECT refund_status FROM orders WHERE id = ?", fixture.orderId()))
        .isEqualTo("REQUIRED");
  }

  @Test
  void should_notExpirePendingOrderBeforeFifteenMinuteGracePeriod() {
    OrderFixture fixture = createOrder(1);
    jdbcTemplate.update(
        "UPDATE reservations SET expires_at = clock_timestamp() - INTERVAL '14 minutes' WHERE id = ?",
        fixture.reservationId());
    expirationService.expireOverdue();

    orderExpirationService.expireOverdue();

    assertThat(paymentFlowResult(fixture))
        .isEqualTo(new PaymentFlowResult("PENDING_PAYMENT", "PENDING", "EXPIRED", 0, 1));
  }

  @Test
  void should_releaseReservationOnlyOnce_when_twoWorkersExpireSameOrderConcurrently()
      throws Exception {
    OrderFixture fixture = createOrder(1);
    jdbcTemplate.update(
        "UPDATE reservations SET expires_at = clock_timestamp() - INTERVAL '16 minutes' WHERE id = ?",
        fixture.reservationId());
    expirationService.expireOverdue();

    List<OrderExpirationCandidate> candidates =
        orderRepository.findExpiredPendingOrderCandidates(10);
    assertThat(candidates).hasSize(1);
    OrderExpirationCandidate candidate = candidates.getFirst();
    CountDownLatch start = new CountDownLatch(1);

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<?> first =
          executor.submit(
              () -> {
                start.await();
                orderExpirationProcessor.expire(candidate);
                return null;
              });
      Future<?> second =
          executor.submit(
              () -> {
                start.await();
                orderExpirationProcessor.expire(candidate);
                return null;
              });

      start.countDown();
      first.get(60, TimeUnit.SECONDS);
      second.get(60, TimeUnit.SECONDS);
    }

    assertThat(paymentFlowResult(fixture))
        .isEqualTo(new PaymentFlowResult("EXPIRED", "PENDING", "EXPIRED", 0, 1));
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT inventory_disposition FROM reservations WHERE id = ?",
                String.class,
                fixture.reservationId()))
        .isEqualTo("RELEASED");
  }

  @Test
  void should_expirePendingOrderAfterGraceAndRequireRefundForLateCapture() {
    OrderFixture fixture = createOrder(1);
    jdbcTemplate.update(
        "UPDATE reservations SET expires_at = clock_timestamp() - INTERVAL '16 minutes' WHERE id = ?",
        fixture.reservationId());
    expirationService.expireOverdue();

    orderExpirationService.expireOverdue();
    orderExpirationService.expireOverdue();

    assertThat(paymentFlowResult(fixture))
        .isEqualTo(new PaymentFlowResult("EXPIRED", "PENDING", "EXPIRED", 0, 1));

    PaymentWebhookRequest lateCapture =
        webhook(fixture, PaymentStatus.SUCCEEDED, "provider-after-order-expiry");
    orderPaymentService.process(lateCapture);
    orderPaymentService.process(lateCapture);

    assertThat(paymentFlowResult(fixture))
        .isEqualTo(new PaymentFlowResult("EXPIRED", "SUCCEEDED", "EXPIRED", 0, 1));
    assertThat(queryString("SELECT fulfillment_status FROM orders WHERE id = ?", fixture.orderId()))
        .isEqualTo("UNFULFILLABLE");
    assertThat(queryString("SELECT refund_status FROM orders WHERE id = ?", fixture.orderId()))
        .isEqualTo("REQUIRED");

    orderPaymentService.process(
        refundWebhook(fixture, PaymentStatus.REFUNDED, "provider-refund-after-expiry"));

    assertThat(paymentFlowResult(fixture))
        .isEqualTo(new PaymentFlowResult("EXPIRED", "REFUNDED", "EXPIRED", 0, 1));
    assertThat(queryString("SELECT refund_status FROM orders WHERE id = ?", fixture.orderId()))
        .isEqualTo("CONFIRMED");
  }

  @Test
  void should_recordFailedPaymentEvidenceWithoutChangingExpiredOrder() {
    OrderFixture fixture = createOrder(1);
    jdbcTemplate.update(
        "UPDATE reservations SET expires_at = clock_timestamp() - INTERVAL '16 minutes' WHERE id = ?",
        fixture.reservationId());
    expirationService.expireOverdue();

    orderExpirationService.expireOverdue();
    orderPaymentService.process(webhook(fixture, PaymentStatus.FAILED, null));

    assertThat(paymentFlowResult(fixture))
        .isEqualTo(new PaymentFlowResult("EXPIRED", "FAILED", "EXPIRED", 0, 1));
  }

  @Test
  void should_releaseReservationStock_when_pendingOrderIsCancelled() {
    OrderFixture fixture = createOrder(1);

    asUser(
        fixture.userId(),
        () -> orderCancellationService.cancel(fixture.orderId(), fixture.userId()));

    assertThat(paymentFlowResult(fixture))
        .isEqualTo(new PaymentFlowResult("CANCELLED", "PENDING", "CANCELLED", 0, 1));
  }

  @Test
  void should_confirmRefundAndCancelTicketsOnlyAfterProviderEvidence() {
    OrderFixture fixture = createOrder(1);
    orderPaymentService.process(webhook(fixture, PaymentStatus.SUCCEEDED, "provider-capture"));

    asUser(
        fixture.userId(),
        () -> orderCancellationService.cancel(fixture.orderId(), fixture.userId()));

    assertThat(paymentFlowResult(fixture))
        .isEqualTo(new PaymentFlowResult("PAID", "SUCCEEDED", "CONFIRMED", 1, 0));
    assertThat(queryString("SELECT refund_status FROM orders WHERE id = ?", fixture.orderId()))
        .isEqualTo("REQUESTED");

    orderPaymentService.process(refundWebhook(fixture, PaymentStatus.REFUNDED, "provider-refund"));

    assertThat(paymentFlowResult(fixture))
        .isEqualTo(new PaymentFlowResult("REFUNDED", "REFUNDED", "CONFIRMED", 1, 0));
    assertThat(
            queryString(
                "SELECT t.status FROM tickets t JOIN order_items oi ON t.order_item_id = oi.id"
                    + " WHERE oi.order_id = ?",
                fixture.orderId()))
        .isEqualTo("CANCELLED");
  }

  @Test
  void should_persistAndDeduplicateThreeIdentities_when_sameWebhookIsRepeated() throws Exception {
    OrderFixture fixture = createOrder(1);
    PaymentWebhookRequest webhook = webhook(fixture, PaymentStatus.SUCCEEDED, "provider-inbox");
    String payload = objectMapper.writeValueAsString(webhook);

    paymentWebhookService.handle(signature(payload), payload);
    paymentWebhookService.handle(signature(payload), payload);

    assertThat(paymentFlowResult(fixture))
        .isEqualTo(new PaymentFlowResult("PAID", "SUCCEEDED", "CONFIRMED", 1, 0));
    assertThat(countRows("provider_events", "provider_event_id", webhook.providerEventId()))
        .isEqualTo(1);
    assertThat(
            countRowsByString(
                "provider_operations", "provider_operation_id", webhook.providerOperationId()))
        .isEqualTo(1);
    assertThat(
            countRowsByString(
                "semantic_applications", "idempotency_key", webhook.providerOperationId()))
        .isEqualTo(1);
  }

  @Test
  void should_applySemanticOperationOnceAcrossDistinctProviderEvents() throws Exception {
    OrderFixture fixture = createOrder(1);
    PaymentWebhookRequest first = webhook(fixture, PaymentStatus.SUCCEEDED, "provider-two-events");
    PaymentWebhookRequest second =
        new PaymentWebhookRequest(
            first.orderId(),
            first.status(),
            first.providerReference(),
            UUID.randomUUID(),
            first.providerOperationId(),
            first.operationKind(),
            first.amount(),
            first.currency());

    handle(first);
    handle(second);

    assertThat(paymentFlowResult(fixture))
        .isEqualTo(new PaymentFlowResult("PAID", "SUCCEEDED", "CONFIRMED", 1, 0));
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM provider_events pe"
                    + " JOIN provider_operations po ON pe.provider_operation_id = po.id"
                    + " WHERE po.provider_operation_id = ?",
                Integer.class,
                first.providerOperationId()))
        .isEqualTo(2);
    assertThat(
            countRowsByString(
                "semantic_applications", "idempotency_key", first.providerOperationId()))
        .isEqualTo(1);
  }

  @Test
  void should_applySemanticOperationOnce_when_distinctProviderEventsArriveConcurrently()
      throws Exception {
    OrderFixture fixture = createOrder(1);
    PaymentWebhookRequest first =
        webhook(fixture, PaymentStatus.SUCCEEDED, "provider-concurrent-events");
    PaymentWebhookRequest second =
        new PaymentWebhookRequest(
            first.orderId(),
            first.status(),
            first.providerReference(),
            UUID.randomUUID(),
            first.providerOperationId(),
            first.operationKind(),
            first.amount(),
            first.currency());
    CountDownLatch start = new CountDownLatch(1);

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<?> firstResult =
          executor.submit(
              () -> {
                start.await();
                handle(first);
                return null;
              });
      Future<?> secondResult =
          executor.submit(
              () -> {
                start.await();
                handle(second);
                return null;
              });

      start.countDown();
      firstResult.get(60, TimeUnit.SECONDS);
      secondResult.get(60, TimeUnit.SECONDS);
    }

    assertThat(paymentFlowResult(fixture))
        .isEqualTo(new PaymentFlowResult("PAID", "SUCCEEDED", "CONFIRMED", 1, 0));
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM provider_events pe"
                    + " JOIN provider_operations po ON pe.provider_operation_id = po.id"
                    + " WHERE po.provider_operation_id = ?",
                Integer.class,
                first.providerOperationId()))
        .isEqualTo(2);
    assertThat(
            countRowsByString(
                "provider_operations", "provider_operation_id", first.providerOperationId()))
        .isEqualTo(1);
    assertThat(
            countRowsByString(
                "semantic_applications", "idempotency_key", first.providerOperationId()))
        .isEqualTo(1);
  }

  @Test
  void should_rejectChangedEvidenceForSameProviderOperation() throws Exception {
    OrderFixture fixture = createOrder(1);
    PaymentWebhookRequest first = webhook(fixture, PaymentStatus.SUCCEEDED, "provider-digest");
    PaymentWebhookRequest changed =
        new PaymentWebhookRequest(
            first.orderId(),
            first.status(),
            first.providerReference(),
            UUID.randomUUID(),
            first.providerOperationId(),
            first.operationKind(),
            new BigDecimal("41.00"),
            first.currency());
    handle(first);

    assertThatThrownBy(() -> handle(changed)).isInstanceOf(DuplicateResourceException.class);
    assertThat(
            countRowsByString(
                "semantic_applications", "idempotency_key", first.providerOperationId()))
        .isEqualTo(1);
  }

  @Test
  void should_rejectUpdatesToAppendOnlyProviderEvidence() throws Exception {
    OrderFixture fixture = createOrder(1);
    PaymentWebhookRequest webhook = webhook(fixture, PaymentStatus.SUCCEEDED, "provider-append");
    handle(webhook);

    assertThatThrownBy(() ->
            jdbcTemplate.update(
                "UPDATE provider_events SET payload_digest = payload_digest"
                    + " WHERE provider_event_id = ?",
                webhook.providerEventId()))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("append-only");
  }

  private void handle(PaymentWebhookRequest webhook) throws Exception {
    String payload = objectMapper.writeValueAsString(webhook);
    paymentWebhookService.handle(signature(payload), payload);
  }

  private String signature(String payload) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(
        new SecretKeySpec(
            paymentProperties.getWebhookSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
  }

  private static PaymentWebhookRequest webhook(
      OrderFixture fixture, PaymentStatus status, String providerReference) {
    return new PaymentWebhookRequest(
        fixture.orderId(),
        status,
        providerReference,
        UUID.randomUUID(),
        "operation-" + UUID.randomUUID(),
        status == PaymentStatus.SUCCEEDED
            ? com.portfolio.ticketing.payment.domain.ProviderOperationKind.PAYMENT_CAPTURE
            : com.portfolio.ticketing.payment.domain.ProviderOperationKind.PAYMENT_FAILURE,
        new BigDecimal("40.00"),
        "USD");
  }

  private static PaymentWebhookRequest refundWebhook(
      OrderFixture fixture, PaymentStatus status, String providerReference) {
    return new PaymentWebhookRequest(
        fixture.orderId(),
        status,
        providerReference,
        UUID.randomUUID(),
        "refund-operation-" + UUID.randomUUID(),
        com.portfolio.ticketing.payment.domain.ProviderOperationKind.PAYMENT_REFUND,
        new BigDecimal("40.00"),
        "USD");
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

  private int countRowsByString(String table, String column, String id) {
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
