package com.portfolio.ticketing.operation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.ticketing.common.exception.BusinessRuleViolationException;
import com.portfolio.ticketing.event.domain.Event;
import com.portfolio.ticketing.event.repository.EventRepository;
import com.portfolio.ticketing.order.api.OrderRequest;
import com.portfolio.ticketing.order.service.OrderCreationResult;
import com.portfolio.ticketing.order.service.OrderService;
import com.portfolio.ticketing.payment.api.PaymentWebhookRequest;
import com.portfolio.ticketing.payment.config.PaymentProperties;
import com.portfolio.ticketing.payment.domain.PaymentStatus;
import com.portfolio.ticketing.payment.domain.ProviderOperationKind;
import com.portfolio.ticketing.payment.service.PaymentWebhookService;
import com.portfolio.ticketing.payment.service.ProviderEventInboxService;
import com.portfolio.ticketing.payment.service.ProviderEventReceipt;
import com.portfolio.ticketing.reservation.api.ReservationRequest;
import com.portfolio.ticketing.reservation.api.ReservationResponse;
import com.portfolio.ticketing.reservation.service.ReservationService;
import com.portfolio.ticketing.support.AbstractIntegrationTest;
import com.portfolio.ticketing.tickettype.domain.TicketType;
import com.portfolio.ticketing.tickettype.repository.TicketTypeRepository;
import com.portfolio.ticketing.venue.domain.Venue;
import com.portfolio.ticketing.venue.repository.VenueRepository;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import org.awaitility.Awaitility;
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
@TestPropertySource(
    properties = {
      "ticketing.operation.recovery-enabled=false",
      "ticketing.reservation.expiration-fixed-delay-ms=600000"
    })
class OperationWorkerIT extends AbstractIntegrationTest {

  @Autowired private ReservationService reservationService;
  @Autowired private OrderService orderService;
  @Autowired private ProviderEventInboxService inboxService;
  @Autowired private OperationJobService jobService;
  @Autowired private PaymentOperationService paymentOperationService;
  @Autowired private PaymentWebhookService paymentWebhookService;
  @Autowired private PaymentProperties paymentProperties;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private TicketTypeRepository ticketTypeRepository;
  @Autowired private EventRepository eventRepository;
  @Autowired private VenueRepository venueRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private DataSource dataSource;

  @Test
  void should_allowOnlyOneWorkerToClaimSameJob() throws Exception {
    PendingOperation pending = pendingOperation(createOrder(), new BigDecimal("40.00"));

    List<Optional<OperationClaim>> claims =
        runConcurrently(
            () -> jobService.claim(pending.jobId(), "worker-a"),
            () -> jobService.claim(pending.jobId(), "worker-b"));

    assertThat(claims.stream().filter(Optional::isPresent)).hasSize(1);
  }

  @Test
  void should_renewLeaseForCurrentExecution() {
    PendingOperation pending = pendingOperation(createOrder(), new BigDecimal("40.00"));
    OperationClaim claim = jobService.claim(pending.jobId(), "renewing-worker").orElseThrow();
    Instant originalLease = leaseExpiration(claim);

    Instant renewedLease = jobService.renewLease(claim);

    assertThat(renewedLease).isAfterOrEqualTo(originalLease);
    assertThat(leaseExpiration(claim)).isEqualTo(renewedLease);
  }

  @Test
  void should_replaceJobAndFencePreviousExecution() {
    OrderFixture fixture = createOrder();
    PendingOperation pending = pendingOperation(fixture, new BigDecimal("40.00"));
    OperationClaim previous = jobService.claim(pending.jobId(), "previous-worker").orElseThrow();

    UUID replacementJobId = jobService.replace(pending.jobId());

    assertThatThrownBy(() -> paymentOperationService.apply(previous))
        .isInstanceOf(OperationFencingException.class);
    assertThat(jobStatus(pending.jobId())).isEqualTo("REPLACED");
    paymentOperationService.processJob(replacementJobId, "replacement-worker");
    assertThat(ticketCount(fixture.orderId())).isEqualTo(1);
    assertThat(semanticCount(pending.request())).isEqualTo(1);
  }

  @Test
  void should_fenceStaleWorkerAfterLeaseExpiration() throws Exception {
    OrderFixture fixture = createOrder();
    PendingOperation pending = pendingOperation(fixture, new BigDecimal("40.00"));
    OperationClaim stale = jobService.claim(pending.jobId(), "stale-worker").orElseThrow();
    jdbcTemplate.update(
        "UPDATE operation_executions SET lease_expires_at ="
            + " clock_timestamp() - interval '1 second' WHERE id = ?",
        stale.executionId());
    OperationClaim current = jobService.claim(pending.jobId(), "current-worker").orElseThrow();

    assertThat(current.fencingToken()).isGreaterThan(stale.fencingToken());
    assertThatThrownBy(() -> paymentOperationService.apply(stale))
        .isInstanceOf(OperationFencingException.class);
    assertThat(semanticCount(pending.request())).isZero();
    assertThat(ticketCount(fixture.orderId())).isZero();

    paymentOperationService.apply(current);
    paymentOperationService.project(current);

    assertThat(ticketCount(fixture.orderId())).isEqualTo(1);
    assertThat(semanticCount(pending.request())).isEqualTo(1);
    assertThat(projectionStatus(pending.jobId())).isEqualTo("COMPLETED");
  }

  @Test
  void should_lockEventBeforeJobAndExecution_when_applyingPayment() throws Exception {
    OrderFixture fixture = createOrder();
    PendingOperation pending = pendingOperation(fixture, new BigDecimal("40.00"));
    OperationClaim claim = jobService.claim(pending.jobId(), "event-first-worker").orElseThrow();
    UUID eventId =
        jdbcTemplate.queryForObject(
            """
            SELECT t.event_id
            FROM orders o
            JOIN reservations r ON r.id = o.reservation_id
            JOIN ticket_types t ON t.id = r.ticket_type_id
            WHERE o.id = ?
            """,
            UUID.class,
            fixture.orderId());

    try (Connection blocker = dataSource.getConnection();
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      blocker.setAutoCommit(false);
      try (PreparedStatement lock =
          blocker.prepareStatement("SELECT status FROM events WHERE id = ? FOR UPDATE")) {
        lock.setObject(1, eventId);
        lock.executeQuery().close();
      }
      int blockerPid;
      try (PreparedStatement pid = blocker.prepareStatement("SELECT pg_backend_pid()");
          var result = pid.executeQuery()) {
        result.next();
        blockerPid = result.getInt(1);
      }

      CompletableFuture<Void> applying =
          CompletableFuture.runAsync(() -> paymentOperationService.apply(claim), executor);
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
                          AND query ILIKE '%FROM events WHERE id%FOR SHARE%'
                        """,
                        Integer.class,
                        blockerPid);
                assertThat(waiting).isNotNull().isGreaterThan(0);
              });

      try (Connection probe = dataSource.getConnection();
          PreparedStatement jobLock =
              probe.prepareStatement("SELECT id FROM operation_jobs WHERE id = ? FOR UPDATE NOWAIT")) {
        jobLock.setObject(1, pending.jobId());
        jobLock.executeQuery().close();
        probe.commit();
      }
      assertThat(semanticCount(pending.request())).isZero();
      blocker.commit();
      applying.get(10, TimeUnit.SECONDS);
    }

    paymentOperationService.project(claim);
    assertThat(ticketCount(fixture.orderId())).isEqualTo(1);
    assertThat(semanticCount(pending.request())).isEqualTo(1);
  }

  @Test
  void should_recoverProjectionWithoutReapplyingBusinessEffects() throws Exception {
    OrderFixture fixture = createOrder();
    PendingOperation pending = pendingOperation(fixture, new BigDecimal("40.00"));
    OperationClaim interrupted = jobService.claim(pending.jobId(), "interrupted").orElseThrow();
    paymentOperationService.apply(interrupted);

    paymentOperationService.processJob(pending.jobId(), "recovery");

    assertThat(ticketCount(fixture.orderId())).isEqualTo(1);
    assertThat(semanticCount(pending.request())).isEqualTo(1);
    assertThat(executionCount(pending.jobId())).isEqualTo(2);
    assertThat(dispositionTypes(pending.jobId())).containsExactlyInAnyOrder("APPLIED", "NO_OP");
    assertThat(projectionStatus(pending.jobId())).isEqualTo("COMPLETED");
  }

  @Test
  void should_retryAndFailWithoutSemanticApplicationWhenEvidenceIsInvalid() {
    OrderFixture fixture = createOrder();
    PendingOperation pending = pendingOperation(fixture, new BigDecimal("41.00"));

    for (int attempt = 0; attempt < 3; attempt++) {
      assertThatThrownBy(() -> paymentOperationService.processJob(pending.jobId(), "retry"))
          .isInstanceOf(BusinessRuleViolationException.class);
    }

    assertThat(jobStatus(pending.jobId())).isEqualTo("FAILED");
    assertThat(executionCount(pending.jobId())).isEqualTo(3);
    assertThat(dispositionTypes(pending.jobId())).containsOnly("FAILED").hasSize(3);
    assertThat(semanticCount(pending.request())).isZero();
    assertThat(ticketCount(fixture.orderId())).isZero();
  }

  @Test
  void should_notRepeatSemanticApplicationInANewCycle() throws Exception {
    OrderFixture fixture = createOrder();
    PendingOperation pending = pendingOperation(fixture, new BigDecimal("40.00"));
    paymentOperationService.processJob(pending.jobId(), "initial-cycle");
    UUID secondJobId = createSecondCycleJob(pending);

    paymentOperationService.processJob(secondJobId, "second-cycle");

    assertThat(ticketCount(fixture.orderId())).isEqualTo(1);
    assertThat(semanticCount(pending.request())).isEqualTo(1);
    assertThat(dispositionTypes(secondJobId)).containsExactly("NO_OP");
    assertThat(projectionStatus(secondJobId)).isEqualTo("COMPLETED");
  }

  @Test
  void should_processConcurrentDuplicateWebhookOnlyOnce() throws Exception {
    OrderFixture fixture = createOrder();
    PaymentWebhookRequest request = webhook(fixture, new BigDecimal("40.00"));
    String payload = objectMapper.writeValueAsString(request);
    String signature = signature(payload);

    runConcurrently(
        () -> {
          paymentWebhookService.handle(signature, payload);
          return null;
        },
        () -> {
          paymentWebhookService.handle(signature, payload);
          return null;
        });

    assertThat(ticketCount(fixture.orderId())).isEqualTo(1);
    assertThat(semanticCount(request)).isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM provider_events WHERE provider_event_id = ?",
                Integer.class,
                request.providerEventId()))
        .isEqualTo(1);
  }

  private PendingOperation pendingOperation(OrderFixture fixture, BigDecimal amount) {
    try {
      PaymentWebhookRequest request = webhook(fixture, amount);
      String payload = objectMapper.writeValueAsString(request);
      ProviderEventReceipt receipt = inboxService.receive(request, payload);
      UUID jobId = jobService.findEffectiveJobIdForEvent(receipt.eventId()).orElseThrow();
      return new PendingOperation(receipt, request, jobId);
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  private UUID createSecondCycleJob(PendingOperation pending) {
    UUID cycleId =
        jdbcTemplate.queryForObject(
            "INSERT INTO operation_cycles (aggregate_type, aggregate_id, cycle_number)"
                + " VALUES ('ORDER', ?, 2) RETURNING id",
            UUID.class,
            pending.request().orderId());
    UUID participationId =
        jdbcTemplate.queryForObject(
            "INSERT INTO operation_participations (cycle_id, provider_event_id)"
                + " VALUES (?, ?) RETURNING id",
            UUID.class,
            cycleId,
            pending.receipt().eventId());
    UUID jobId =
        jdbcTemplate.queryForObject(
            "INSERT INTO operation_jobs (participation_id, generation) VALUES (?, 1) RETURNING id",
            UUID.class,
            participationId);
    jdbcTemplate.update(
        "INSERT INTO operation_projections (participation_id, technical_status,"
            + " commercial_status, financial_status, fencing_token)"
            + " VALUES (?, 'PENDING', 'PENDING', 'PENDING', 0)",
        participationId);
    return jobId;
  }

  private OrderFixture createOrder() {
    Instant now = Instant.now();
    UUID userId = createUser();
    Venue venue = venueRepository.save(new Venue("Worker Arena", "Lima", "Main St 1", 500));
    Event event =
        new Event(
            userId,
            venue,
            "Worker Show",
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
                1,
                now.minusSeconds(120),
                now.plusSeconds(3600),
                4));
    ReservationResponse reservation =
        asUser(
            userId,
            () -> reservationService.create(userId, new ReservationRequest(ticketType.getId(), 1)));
    OrderCreationResult order =
        asUser(
            userId,
            () ->
                orderService.create(
                    userId, "checkout-" + UUID.randomUUID(), new OrderRequest(reservation.id())));
    return new OrderFixture(userId, order.response().id());
  }

  private UUID createUser() {
    return jdbcTemplate.queryForObject(
        "INSERT INTO users (email, password_hash, full_name, role)"
            + " VALUES (?, 'hash', 'Worker Buyer', 'USER') RETURNING id",
        UUID.class,
        "worker-" + UUID.randomUUID() + "@example.com");
  }

  private static PaymentWebhookRequest webhook(OrderFixture fixture, BigDecimal amount) {
    return new PaymentWebhookRequest(
        fixture.orderId(),
        PaymentStatus.SUCCEEDED,
        "provider-" + UUID.randomUUID(),
        UUID.randomUUID(),
        "operation-" + UUID.randomUUID(),
        ProviderOperationKind.PAYMENT_CAPTURE,
        amount,
        "USD");
  }

  private String signature(String payload) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(
        new SecretKeySpec(
            paymentProperties.getWebhookSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
  }

  private <T> List<T> runConcurrently(Callable<T> first, Callable<T> second) throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      Future<T> firstResult = executor.submit(coordinate(first, ready, start));
      Future<T> secondResult = executor.submit(coordinate(second, ready, start));
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      return Arrays.asList(
          firstResult.get(20, TimeUnit.SECONDS), secondResult.get(20, TimeUnit.SECONDS));
    } finally {
      start.countDown();
      executor.shutdownNow();
    }
  }

  private static <T> Callable<T> coordinate(
      Callable<T> action, CountDownLatch ready, CountDownLatch start) {
    return () -> {
      ready.countDown();
      if (!start.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Concurrent start timed out");
      }
      return action.call();
    };
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

  private Instant leaseExpiration(OperationClaim claim) {
    return jdbcTemplate
        .queryForObject(
            "SELECT lease_expires_at FROM operation_executions WHERE id = ?",
            java.time.OffsetDateTime.class,
            claim.executionId())
        .toInstant();
  }

  private int ticketCount(UUID orderId) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM tickets t JOIN order_items oi ON t.order_item_id = oi.id"
            + " WHERE oi.order_id = ?",
        Integer.class,
        orderId);
  }

  private int semanticCount(PaymentWebhookRequest request) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM semantic_applications WHERE idempotency_key = ?",
        Integer.class,
        request.providerOperationId());
  }

  private int executionCount(UUID jobId) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM operation_executions WHERE job_id = ?", Integer.class, jobId);
  }

  private List<String> dispositionTypes(UUID jobId) {
    return jdbcTemplate.query(
        "SELECT od.disposition_type FROM operation_dispositions od"
            + " JOIN operation_executions oe ON oe.id = od.execution_id WHERE oe.job_id = ?",
        (resultSet, rowNum) -> resultSet.getString(1),
        jobId);
  }

  private String jobStatus(UUID jobId) {
    return jdbcTemplate.queryForObject(
        "SELECT status FROM operation_jobs WHERE id = ?", String.class, jobId);
  }

  private String projectionStatus(UUID jobId) {
    return jdbcTemplate.queryForObject(
        "SELECT op.technical_status FROM operation_projections op"
            + " JOIN operation_jobs oj ON oj.participation_id = op.participation_id"
            + " WHERE oj.id = ?",
        String.class,
        jobId);
  }

  private record OrderFixture(UUID userId, UUID orderId) {}

  private record PendingOperation(
      ProviderEventReceipt receipt, PaymentWebhookRequest request, UUID jobId) {}
}
