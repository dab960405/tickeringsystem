package com.portfolio.ticketing.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portfolio.ticketing.common.exception.BusinessRuleViolationException;
import com.portfolio.ticketing.event.domain.Event;
import com.portfolio.ticketing.event.repository.EventRepository;
import com.portfolio.ticketing.order.api.OrderRequest;
import com.portfolio.ticketing.order.repository.OrderRepository;
import com.portfolio.ticketing.order.service.OrderCancellationService;
import com.portfolio.ticketing.order.service.OrderCreationResult;
import com.portfolio.ticketing.order.service.OrderPaymentService;
import com.portfolio.ticketing.order.service.OrderService;
import com.portfolio.ticketing.order.service.TicketIssuanceResult;
import com.portfolio.ticketing.order.service.TicketService;
import com.portfolio.ticketing.payment.api.PaymentWebhookRequest;
import com.portfolio.ticketing.payment.domain.PaymentStatus;
import com.portfolio.ticketing.payment.domain.ProviderOperationKind;
import com.portfolio.ticketing.reservation.api.ReservationRequest;
import com.portfolio.ticketing.reservation.api.ReservationResponse;
import com.portfolio.ticketing.reservation.service.ReservationService;
import com.portfolio.ticketing.support.AbstractIntegrationTest;
import com.portfolio.ticketing.tickettype.domain.TicketType;
import com.portfolio.ticketing.tickettype.repository.TicketTypeRepository;
import com.portfolio.ticketing.venue.domain.Venue;
import com.portfolio.ticketing.venue.repository.VenueRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
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
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@TestPropertySource(properties = "ticketing.reservation.expiration-fixed-delay-ms=600000")
class CommercialIssuanceIT extends AbstractIntegrationTest {

  private static final BigDecimal UNIT_PRICE = new BigDecimal("40.00");

  @Autowired private ReservationService reservationService;
  @Autowired private OrderService orderService;
  @Autowired private OrderPaymentService orderPaymentService;
  @Autowired private OrderCancellationService orderCancellationService;
  @Autowired private TicketService ticketService;
  @Autowired private OrderRepository orderRepository;
  @Autowired private TicketTypeRepository ticketTypeRepository;
  @Autowired private EventRepository eventRepository;
  @Autowired private VenueRepository venueRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private DataSource dataSource;

  @Test
  void should_issueIndividualUnitsAndTickets_when_multiUnitPurchaseIsPaid() {
    OrderFixture fixture = createOrder(3);

    orderPaymentService.process(capture(fixture));

    assertThat(count("SELECT COUNT(*) FROM commercial_generations WHERE order_id = ?", fixture))
        .isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM commercial_units WHERE order_id = ?", fixture))
        .isEqualTo(3);
    assertThat(
            count("SELECT COUNT(*) FROM commercial_unit_generations WHERE order_id = ?", fixture))
        .isEqualTo(3);
    assertThat(
            count(
                "SELECT COUNT(DISTINCT commercial_unit_id) FROM tickets"
                    + " WHERE order_id = ? AND status = 'VALID'",
                fixture))
        .isEqualTo(3);
    assertThat(
            jdbcTemplate.queryForList(
                "SELECT unit_number FROM commercial_units WHERE order_id = ? ORDER BY unit_number",
                Integer.class,
                fixture.orderId()))
        .containsExactly(1, 2, 3);
  }

  @Test
  void should_notDuplicateUnitsOrTickets_when_captureIsRetried() {
    OrderFixture fixture = createOrder(2);
    PaymentWebhookRequest capture = capture(fixture);

    orderPaymentService.process(capture);
    orderPaymentService.process(capture);

    assertThat(count("SELECT COUNT(*) FROM commercial_generations WHERE order_id = ?", fixture))
        .isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM tickets WHERE order_id = ?", fixture)).isEqualTo(2);
  }

  @Test
  void should_issueExactlyOnce_when_issuanceRunsConcurrently() throws Exception {
    OrderFixture fixture = createOrder(2);
    jdbcTemplate.update("UPDATE orders SET status = 'PAID' WHERE id = ?", fixture.orderId());
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    Callable<TicketIssuanceResult> issue =
        () -> {
          ready.countDown();
          start.await();
          return transactionTemplate.execute(status ->
              ticketService.issueForOrder(
                  orderRepository.findById(fixture.orderId()).orElseThrow()));
        };
    try {
      Future<TicketIssuanceResult> first = executor.submit(issue);
      Future<TicketIssuanceResult> second = executor.submit(issue);
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      List<TicketIssuanceResult> results =
          List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));

      assertThat(results)
          .extracting(TicketIssuanceResult::issued)
          .containsExactlyInAnyOrder(true, false);
      assertThat(results)
          .extracting(TicketIssuanceResult::generationId)
          .containsOnly(results.getFirst().generationId());
    } finally {
      executor.shutdownNow();
    }
    assertThat(count("SELECT COUNT(*) FROM commercial_generations WHERE order_id = ?", fixture))
        .isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM tickets WHERE order_id = ?", fixture)).isEqualTo(2);
  }

  @Test
  void should_keepUnitIdentityAndSupersedePreviousGeneration_when_ticketsAreReissued() {
    OrderFixture fixture = createOrder(2);
    orderPaymentService.process(capture(fixture));
    List<UUID> unitsBefore = unitIds(fixture);
    List<String> codesBefore = validCodes(fixture);

    TicketIssuanceResult reissued = ticketService.reissueForOrder(fixture.orderId());

    assertThat(reissued.generationNumber()).isEqualTo(2);
    assertThat(unitIds(fixture)).containsExactlyElementsOf(unitsBefore);
    assertThat(validCodes(fixture)).hasSize(2).doesNotContainAnyElementsOf(codesBefore);
    assertThat(
            jdbcTemplate.queryForList(
                "SELECT status FROM commercial_generations WHERE order_id = ?"
                    + " ORDER BY generation_number",
                String.class,
                fixture.orderId()))
        .containsExactly("SUPERSEDED", "ACTIVE");
    assertThat(
            count(
                "SELECT COUNT(*) FROM tickets WHERE order_id = ? AND status = 'CANCELLED'",
                fixture))
        .isEqualTo(2);
    assertThat(
            jdbcTemplate.queryForList(
                "SELECT commercial_unit_id FROM tickets WHERE generation_id = ?"
                    + " ORDER BY commercial_unit_id",
                UUID.class,
                reissued.generationId()))
        .containsExactlyInAnyOrderElementsOf(unitsBefore);
  }

  @Test
  void should_serializeConcurrentReissues_withoutDuplicatingUnitsOrActiveGenerations()
      throws Exception {
    OrderFixture fixture = createOrder(2);
    orderPaymentService.process(capture(fixture));
    List<UUID> unitsBefore = unitIds(fixture);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    Callable<TicketIssuanceResult> reissue =
        () -> {
          ready.countDown();
          start.await();
          return ticketService.reissueForOrder(fixture.orderId());
        };
    try {
      Future<TicketIssuanceResult> first = executor.submit(reissue);
      Future<TicketIssuanceResult> second = executor.submit(reissue);
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      List<TicketIssuanceResult> results =
          List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));

      assertThat(results)
          .extracting(TicketIssuanceResult::generationNumber)
          .containsExactlyInAnyOrder(2, 3);
    } finally {
      executor.shutdownNow();
    }
    assertThat(unitIds(fixture)).containsExactlyElementsOf(unitsBefore);
    assertThat(
            count(
                "SELECT COUNT(*) FROM commercial_generations WHERE order_id = ?"
                    + " AND status = 'ACTIVE'",
                fixture))
        .isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM tickets WHERE order_id = ?", fixture)).isEqualTo(6);
    assertThat(
            count("SELECT COUNT(*) FROM tickets WHERE order_id = ? AND status = 'VALID'", fixture))
        .isEqualTo(2);
  }

  @Test
  void should_preserveCommercialIdentity_when_reissueRacesWithRefundConfirmation()
      throws Exception {
    OrderFixture fixture = createOrder(2);
    orderPaymentService.process(capture(fixture));
    List<UUID> unitsBefore = unitIds(fixture);
    asUser(
        fixture.userId(),
        () -> orderCancellationService.cancel(fixture.orderId(), fixture.userId()));
    PaymentWebhookRequest refund = refund(fixture);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    Callable<String> reissue =
        () -> {
          ready.countDown();
          start.await();
          try {
            ticketService.reissueForOrder(fixture.orderId());
            return "REISSUED";
          } catch (BusinessRuleViolationException exception) {
            return "REJECTED";
          }
        };
    Callable<String> confirmRefund =
        () -> {
          ready.countDown();
          start.await();
          orderPaymentService.process(refund);
          return "REFUNDED";
        };
    try {
      Future<String> reissued = executor.submit(reissue);
      Future<String> refunded = executor.submit(confirmRefund);
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      assertThat(reissued.get(30, TimeUnit.SECONDS)).isIn("REISSUED", "REJECTED");
      assertThat(refunded.get(30, TimeUnit.SECONDS)).isEqualTo("REFUNDED");
    } finally {
      executor.shutdownNow();
    }

    assertThat(unitIds(fixture)).containsExactlyElementsOf(unitsBefore);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM orders WHERE id = ?", String.class, fixture.orderId()))
        .isEqualTo("REFUNDED");
    assertThat(
            count(
                "SELECT COUNT(*) FROM commercial_generations WHERE order_id = ?"
                    + " AND status = 'ACTIVE'",
                fixture))
        .isZero();
    assertThat(
            count("SELECT COUNT(*) FROM tickets WHERE order_id = ? AND status = 'VALID'", fixture))
        .isZero();
    assertThat(
            count(
                "SELECT COUNT(*) FROM tickets WHERE order_id = ? AND status = 'CANCELLED'",
                fixture))
        .isEqualTo(count("SELECT COUNT(*) FROM tickets WHERE order_id = ?", fixture));
  }

  @Test
  void should_rejectReissue_when_aTicketWasUsed() {
    OrderFixture fixture = createOrder(1);
    orderPaymentService.process(capture(fixture));
    jdbcTemplate.update("UPDATE tickets SET status = 'USED' WHERE order_id = ?", fixture.orderId());

    assertThatThrownBy(() -> ticketService.reissueForOrder(fixture.orderId()))
        .isInstanceOf(BusinessRuleViolationException.class);
    assertThat(
            count(
                "SELECT COUNT(*) FROM commercial_generations WHERE order_id = ?"
                    + " AND status = 'ACTIVE'",
                fixture))
        .isEqualTo(1);
  }

  @Test
  void should_cancelActiveGeneration_when_refundIsConfirmed() {
    OrderFixture fixture = createOrder(2);
    orderPaymentService.process(capture(fixture));
    asUser(
        fixture.userId(),
        () -> orderCancellationService.cancel(fixture.orderId(), fixture.userId()));

    orderPaymentService.process(refund(fixture));

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM commercial_generations WHERE order_id = ?",
                String.class,
                fixture.orderId()))
        .isEqualTo("CANCELLED");
    assertThat(
            count("SELECT COUNT(*) FROM tickets WHERE order_id = ? AND status = 'VALID'", fixture))
        .isZero();
    assertThatThrownBy(() -> ticketService.reissueForOrder(fixture.orderId()))
        .isInstanceOf(BusinessRuleViolationException.class);
  }

  @Test
  void should_neverIssueUnits_when_paymentFails() {
    OrderFixture fixture = createOrder(2);

    orderPaymentService.process(failure(fixture));

    assertThat(count("SELECT COUNT(*) FROM commercial_generations WHERE order_id = ?", fixture))
        .isZero();
    assertThat(count("SELECT COUNT(*) FROM commercial_units WHERE order_id = ?", fixture)).isZero();
  }

  @Test
  void should_rejectReactivationOfSupersededGeneration_inDatabase() {
    OrderFixture fixture = createOrder(1);
    orderPaymentService.process(capture(fixture));
    ticketService.reissueForOrder(fixture.orderId());

    assertThatThrownBy(() ->
            jdbcTemplate.update(
                "UPDATE commercial_generations SET status = 'ACTIVE'"
                    + " WHERE order_id = ? AND generation_number = 1",
                fixture.orderId()))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void should_rejectSecondActiveGenerationAndDuplicateTicket_inDatabase() {
    OrderFixture fixture = createOrder(1);
    orderPaymentService.process(capture(fixture));
    Map<String, Object> ticket =
        jdbcTemplate.queryForMap(
            "SELECT order_item_id, commercial_unit_id, generation_id FROM tickets WHERE order_id = ?",
            fixture.orderId());

    assertThatThrownBy(() ->
            jdbcTemplate.update(
                "INSERT INTO commercial_generations (order_id, generation_number, status)"
                    + " VALUES (?, 2, 'ACTIVE')",
                fixture.orderId()))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() ->
            jdbcTemplate.update(
                "INSERT INTO tickets (order_item_id, order_id, commercial_unit_id,"
                    + " generation_id, code) VALUES (?, ?, ?, ?, ?)",
                ticket.get("order_item_id"),
                fixture.orderId(),
                ticket.get("commercial_unit_id"),
                ticket.get("generation_id"),
                "duplicate-" + UUID.randomUUID()))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void should_rejectCrossOrderLinks_inDatabase() {
    OrderFixture first = createOrder(1);
    OrderFixture second = createOrder(1);
    orderPaymentService.process(capture(first));
    orderPaymentService.process(capture(second));
    UUID foreignUnit =
        jdbcTemplate.queryForObject(
            "SELECT id FROM commercial_units WHERE order_id = ?", UUID.class, second.orderId());
    UUID generation =
        jdbcTemplate.queryForObject(
            "SELECT id FROM commercial_generations WHERE order_id = ?",
            UUID.class,
            first.orderId());

    assertThatThrownBy(() ->
            jdbcTemplate.update(
                "INSERT INTO commercial_unit_generations"
                    + " (order_id, commercial_unit_id, generation_id) VALUES (?, ?, ?)",
                first.orderId(),
                foreignUnit,
                generation))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void should_backfillLegacyTicketsIntoGenerationOne_whenMigratingFromV15() {
    String schema = "legacy_" + UUID.randomUUID().toString().replace("-", "");
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .locations("classpath:db/migration")
        .target("15")
        .load()
        .migrate();
    JdbcTemplate legacy = new JdbcTemplate(dataSource);
    try {
      LegacyIds ids = insertLegacyPurchase(legacy, schema);

      Flyway.configure()
          .dataSource(dataSource)
          .schemas(schema)
          .locations("classpath:db/migration")
          .load()
          .migrate();

      assertThat(
              legacy.queryForObject(
                  "SELECT status FROM " + schema + ".commercial_generations WHERE order_id = ?",
                  String.class,
                  ids.orderId()))
          .isEqualTo("ACTIVE");
      assertThat(
              legacy.queryForObject(
                  "SELECT COUNT(*) FROM " + schema + ".commercial_units WHERE order_id = ?",
                  Integer.class,
                  ids.orderId()))
          .isEqualTo(2);
      assertThat(
              legacy.queryForList(
                  "SELECT u.unit_number FROM "
                      + schema
                      + ".tickets t JOIN "
                      + schema
                      + ".commercial_units u ON u.id = t.commercial_unit_id"
                      + " WHERE t.order_id = ? ORDER BY t.code",
                  Integer.class,
                  ids.orderId()))
          .containsExactly(1, 2);
      assertThat(
              legacy.queryForObject(
                  "SELECT COUNT(*) FROM "
                      + schema
                      + ".tickets WHERE generation_id IS NULL OR commercial_unit_id IS NULL",
                  Integer.class))
          .isZero();
    } finally {
      legacy.execute("DROP SCHEMA " + schema + " CASCADE");
    }
  }

  @Test
  void should_backfillAllLegacyTicketStatusesAndCancelledOrders_whenMigratingFromV15() {
    String schema = "legacy_" + UUID.randomUUID().toString().replace("-", "");
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .locations("classpath:db/migration")
        .target("15")
        .load()
        .migrate();
    JdbcTemplate legacy = new JdbcTemplate(dataSource);
    try {
      LegacyIds mixed = insertLegacyPurchase(legacy, schema, "VALID", "USED", "CANCELLED");
      LegacyIds cancelled = insertLegacyPurchase(legacy, schema, "CANCELLED");

      Flyway.configure()
          .dataSource(dataSource)
          .schemas(schema)
          .locations("classpath:db/migration")
          .load()
          .migrate();

      assertThat(
              legacy.queryForObject(
                  "SELECT status FROM " + schema + ".commercial_generations WHERE order_id = ?",
                  String.class,
                  mixed.orderId()))
          .isEqualTo("ACTIVE");
      assertThat(
              legacy.queryForObject(
                  "SELECT status FROM " + schema + ".commercial_generations WHERE order_id = ?",
                  String.class,
                  cancelled.orderId()))
          .isEqualTo("CANCELLED");
      assertThat(
              legacy.queryForObject(
                  "SELECT COUNT(*) FROM " + schema + ".commercial_units WHERE order_id = ?",
                  Integer.class,
                  mixed.orderId()))
          .isEqualTo(3);
      assertThat(
              legacy.queryForObject(
                  "SELECT COUNT(*) FROM " + schema + ".commercial_units WHERE order_id = ?",
                  Integer.class,
                  cancelled.orderId()))
          .isEqualTo(1);
      assertThat(
              legacy.queryForList(
                  "SELECT status FROM " + schema + ".tickets WHERE order_id = ?",
                  String.class,
                  mixed.orderId()))
          .containsExactlyInAnyOrder("VALID", "USED", "CANCELLED");
      assertThat(
              legacy.queryForObject(
                  "SELECT COUNT(*) FROM "
                      + schema
                      + ".commercial_unit_generations WHERE order_id IN (?, ?)",
                  Integer.class,
                  mixed.orderId(),
                  cancelled.orderId()))
          .isEqualTo(4);
      assertThat(
              legacy.queryForObject(
                  "SELECT COUNT(*) FROM "
                      + schema
                      + ".tickets WHERE order_id IN (?, ?) AND (generation_id IS NULL"
                      + " OR commercial_unit_id IS NULL OR order_id IS NULL)",
                  Integer.class,
                  mixed.orderId(),
                  cancelled.orderId()))
          .isZero();
    } finally {
      legacy.execute("DROP SCHEMA " + schema + " CASCADE");
    }
  }

  private LegacyIds insertLegacyPurchase(
      JdbcTemplate legacy, String schema, String... requestedStatuses) {
    String[] ticketStatuses =
        requestedStatuses.length == 0 ? new String[] {"VALID", "VALID"} : requestedStatuses;
    int quantity = ticketStatuses.length;
    String prefix = schema + ".";
    UUID userId =
        legacy.queryForObject(
            "INSERT INTO "
                + prefix
                + "users (email, password_hash, full_name, role)"
                + " VALUES (?, 'hash', 'Legacy', 'USER') RETURNING id",
            UUID.class,
            "legacy-" + UUID.randomUUID() + "@example.com");
    UUID venueId =
        legacy.queryForObject(
            "INSERT INTO "
                + prefix
                + "venues (name, city, address, capacity)"
                + " VALUES ('Arena', 'Lima', 'Main 1', 100) RETURNING id",
            UUID.class);
    UUID eventId =
        legacy.queryForObject(
            "INSERT INTO "
                + prefix
                + "events (organizer_id, venue_id, title, category, starts_at, ends_at, status)"
                + " VALUES (?, ?, 'Legacy', 'MUSIC', now() + interval '2 day',"
                + " now() + interval '3 day', 'PUBLISHED') RETURNING id",
            UUID.class,
            userId,
            venueId);
    UUID ticketTypeId =
        legacy.queryForObject(
            "INSERT INTO "
                + prefix
                + "ticket_types (event_id, name, price, total_quantity, available_quantity,"
                + " sale_starts_at, sale_ends_at, max_per_order)"
                + " VALUES (?, 'General', 40.00, 10, ?, now() - interval '1 day',"
                + " now() + interval '1 day', 4) RETURNING id",
            UUID.class,
            eventId,
            10 - quantity);
    UUID reservationId =
        legacy.queryForObject(
            "INSERT INTO "
                + prefix
                + "reservations (user_id, ticket_type_id, quantity, status, expires_at,"
                + " inventory_disposition)"
                + " VALUES (?, ?, ?, 'CONFIRMED', now(), 'CONSUMED') RETURNING id",
            UUID.class,
            userId,
            ticketTypeId,
            quantity);
    UUID orderId =
        legacy.queryForObject(
            "INSERT INTO "
                + prefix
                + "orders (user_id, reservation_id, status, total_amount, idempotency_key,"
                + " fulfillment_status) VALUES (?, ?, 'PAID', ?, ?, 'FULFILLED') RETURNING id",
            UUID.class,
            userId,
            reservationId,
            UNIT_PRICE.multiply(BigDecimal.valueOf(quantity)),
            "legacy-" + UUID.randomUUID());
    UUID itemId =
        legacy.queryForObject(
            "INSERT INTO "
                + prefix
                + "order_items (order_id, ticket_type_id, quantity, unit_price)"
                + " VALUES (?, ?, ?, 40.00) RETURNING id",
            UUID.class,
            orderId,
            ticketTypeId,
            quantity);
    for (int index = 0; index < quantity; index++) {
      legacy.update(
          "INSERT INTO "
              + prefix
              + "tickets (order_item_id, code, status, created_at)"
              + " VALUES (?, ?, ?, now() - (? * interval '1 second'))",
          itemId,
          "legacy-" + orderId + "-" + String.format("%02d", index),
          ticketStatuses[index],
          quantity - index);
    }
    return new LegacyIds(orderId);
  }

  private List<UUID> unitIds(OrderFixture fixture) {
    return jdbcTemplate.queryForList(
        "SELECT id FROM commercial_units WHERE order_id = ? ORDER BY unit_number",
        UUID.class,
        fixture.orderId());
  }

  private List<String> validCodes(OrderFixture fixture) {
    return jdbcTemplate.queryForList(
        "SELECT code FROM tickets WHERE order_id = ? AND status = 'VALID'",
        String.class,
        fixture.orderId());
  }

  private int count(String sql, OrderFixture fixture) {
    return jdbcTemplate.queryForObject(sql, Integer.class, fixture.orderId());
  }

  private static PaymentWebhookRequest capture(OrderFixture fixture) {
    return webhook(
        fixture,
        PaymentStatus.SUCCEEDED,
        "capture-" + fixture.orderId(),
        ProviderOperationKind.PAYMENT_CAPTURE);
  }

  private static PaymentWebhookRequest failure(OrderFixture fixture) {
    return webhook(fixture, PaymentStatus.FAILED, null, ProviderOperationKind.PAYMENT_FAILURE);
  }

  private static PaymentWebhookRequest refund(OrderFixture fixture) {
    return webhook(
        fixture,
        PaymentStatus.REFUNDED,
        "refund-" + fixture.orderId(),
        ProviderOperationKind.PAYMENT_REFUND);
  }

  private static PaymentWebhookRequest webhook(
      OrderFixture fixture,
      PaymentStatus status,
      String providerReference,
      ProviderOperationKind kind) {
    return new PaymentWebhookRequest(
        fixture.orderId(),
        status,
        providerReference,
        UUID.randomUUID(),
        "operation-" + UUID.randomUUID(),
        kind,
        UNIT_PRICE.multiply(BigDecimal.valueOf(fixture.quantity())),
        "USD");
  }

  private OrderFixture createOrder(int quantity) {
    Instant now = Instant.now();
    UUID userId = createUser();
    Venue venue = venueRepository.save(new Venue("Arena", "Lima", "Main St 1", 500));
    Event event =
        new Event(
            userId,
            venue,
            "Issuance Show",
            null,
            "MUSIC",
            now.plusSeconds(7200),
            now.plusSeconds(10800));
    event.publish();
    eventRepository.saveAndFlush(event);
    TicketType ticketType =
        ticketTypeRepository.saveAndFlush(
            new TicketType(
                event, "General", UNIT_PRICE, 10, now.minusSeconds(120), now.plusSeconds(3600), 4));
    ReservationResponse reservation =
        asUser(
            userId,
            () ->
                reservationService.create(
                    userId, new ReservationRequest(ticketType.getId(), quantity)));
    OrderCreationResult order =
        asUser(
            userId,
            () ->
                orderService.create(
                    userId, "checkout-" + UUID.randomUUID(), new OrderRequest(reservation.id())));
    return new OrderFixture(userId, order.response().id(), quantity);
  }

  private UUID createUser() {
    return jdbcTemplate.queryForObject(
        "INSERT INTO users (email, password_hash, full_name, role)"
            + " VALUES (?, 'hash', 'Issuance Buyer', 'USER') RETURNING id",
        UUID.class,
        "issuance-" + UUID.randomUUID() + "@example.com");
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

  private record OrderFixture(UUID userId, UUID orderId, int quantity) {}

  private record LegacyIds(UUID orderId) {}
}
