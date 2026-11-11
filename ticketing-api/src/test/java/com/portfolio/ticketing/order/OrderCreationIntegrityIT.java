package com.portfolio.ticketing.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.ticketing.event.domain.Event;
import com.portfolio.ticketing.event.repository.EventRepository;
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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "ticketing.reservation.expiration-fixed-delay-ms=600000")
class OrderCreationIntegrityIT extends AbstractIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private ReservationService reservationService;
  @Autowired private TicketTypeRepository ticketTypeRepository;
  @Autowired private EventRepository eventRepository;
  @Autowired private VenueRepository venueRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void should_return409_when_reservationAlreadyHasAnOrderWithAnotherKey() throws Exception {
    OrderFixture fixture = createReservation();
    MvcResult first = createOrder(fixture, "checkout-first");
    MvcResult second = createOrder(fixture, "checkout-second");

    assertThat(first.getResponse().getStatus()).isEqualTo(201);
    assertThat(second.getResponse().getStatus()).isEqualTo(409);
    assertThat(second.getResponse().getContentAsString())
        .contains("An order already exists for this reservation");
    assertThat(problemStatus(second)).isEqualTo(409);
    assertThat(countOrders(fixture.reservationId())).isEqualTo(1);
    assertThat(countPayments(fixture.reservationId())).isEqualTo(1);
  }

  @Test
  void should_return200WithSameOrder_when_sameKeyAndReservationAreRepeated() throws Exception {
    OrderFixture fixture = createReservation();
    MvcResult first = createOrder(fixture, "checkout-repeat");
    MvcResult repeated = createOrder(fixture, "checkout-repeat");

    assertThat(first.getResponse().getStatus()).isEqualTo(201);
    assertThat(repeated.getResponse().getStatus()).isEqualTo(200);
    assertThat(orderId(first)).isEqualTo(orderId(repeated));
    assertThat(countOrders(fixture.reservationId())).isEqualTo(1);
    assertThat(countPayments(fixture.reservationId())).isEqualTo(1);
  }

  @Test
  void should_returnOne201AndOne409_when_differentKeysRaceForSameReservation() throws Exception {
    OrderFixture fixture = createReservation();
    List<MvcResult> results = runConcurrently(fixture, "checkout-race-a", "checkout-race-b");

    assertThat(statuses(results)).containsExactlyInAnyOrder(201, 409);
    assertConflictIsProblemDetail(results);
    assertThat(countOrders(fixture.reservationId())).isEqualTo(1);
    assertThat(countPayments(fixture.reservationId())).isEqualTo(1);
  }

  @Test
  void should_neverReturn500_when_sameKeyAndReservationAreCreatedConcurrently() throws Exception {
    OrderFixture fixture = createReservation();
    List<MvcResult> results = runConcurrently(fixture, "checkout-same-race", "checkout-same-race");

    assertThat(statuses(results)).contains(201);
    assertThat(statuses(results))
        .allMatch(status -> status == 201 || status == 200 || status == 409);
    assertConflictIsProblemDetail(results);
    assertThat(countOrders(fixture.reservationId())).isEqualTo(1);
    assertThat(countPayments(fixture.reservationId())).isEqualTo(1);
  }

  private List<MvcResult> runConcurrently(OrderFixture fixture, String keyOne, String keyTwo)
      throws Exception {
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<MvcResult> first =
          executor.submit(() -> concurrentCreate(fixture, keyOne, ready, start));
      Future<MvcResult> second =
          executor.submit(() -> concurrentCreate(fixture, keyTwo, ready, start));
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      return List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
    } finally {
      start.countDown();
      executor.shutdownNow();
    }
  }

  private MvcResult concurrentCreate(
      OrderFixture fixture, String key, CountDownLatch ready, CountDownLatch start)
      throws Exception {
    ready.countDown();
    if (!start.await(10, TimeUnit.SECONDS)) {
      throw new IllegalStateException("Concurrent order creation did not start");
    }
    return createOrder(fixture, key);
  }

  private MvcResult createOrder(OrderFixture fixture, String key) throws Exception {
    return mockMvc
        .perform(
            post("/api/v1/orders")
                .with(userJwt(fixture.userId()))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reservationId\":\"" + fixture.reservationId() + "\"}"))
        .andReturn();
  }

  private RequestPostProcessor userJwt(UUID userId) {
    return jwt()
        .jwt(token -> token.subject(userId.toString()).claim("role", "USER"))
        .authorities(new SimpleGrantedAuthority("ROLE_USER"));
  }

  private OrderFixture createReservation() {
    Instant now = Instant.now();
    UUID userId = createUser();
    Venue venue = venueRepository.save(new Venue("Integrity Arena", "Cali", "Main St 1", 500));
    Event event =
        new Event(
            userId,
            venue,
            "Order Integrity Show",
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
                5,
                now.minusSeconds(120),
                now.plusSeconds(3600),
                4));
    ReservationResponse reservation =
        asUser(
            userId,
            () -> reservationService.create(userId, new ReservationRequest(ticketType.getId(), 1)));
    return new OrderFixture(userId, reservation.id());
  }

  private UUID createUser() {
    return jdbcTemplate.queryForObject(
        "INSERT INTO users (email, password_hash, full_name, role)"
            + " VALUES (?, 'hash', 'Integrity Buyer', 'USER') RETURNING id",
        UUID.class,
        "integrity-" + UUID.randomUUID() + "@example.com");
  }

  private <T> T asUser(UUID userId, java.util.function.Supplier<T> action) {
    org.springframework.security.core.context.SecurityContext context =
        new org.springframework.security.core.context.SecurityContextImpl();
    context.setAuthentication(
        new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
            userId.toString(), null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    org.springframework.security.core.context.SecurityContextHolder.setContext(context);
    try {
      return action.get();
    } finally {
      org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }
  }

  private List<Integer> statuses(List<MvcResult> results) {
    return results.stream().map(result -> result.getResponse().getStatus()).toList();
  }

  private void assertConflictIsProblemDetail(List<MvcResult> results) throws Exception {
    for (MvcResult result : results) {
      if (result.getResponse().getStatus() == 409) {
        JsonNode problem = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(problem.path("status").asInt()).isEqualTo(409);
        assertThat(problem.path("type").asText())
            .startsWith("https://ticketing.portfolio.com/problems/");
      }
    }
  }

  private int problemStatus(MvcResult result) throws Exception {
    return objectMapper.readTree(result.getResponse().getContentAsString()).path("status").asInt();
  }

  private int countOrders(UUID reservationId) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM orders WHERE reservation_id = ?", Integer.class, reservationId);
  }

  private int countPayments(UUID reservationId) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM payments p JOIN orders o ON o.id = p.order_id"
            + " WHERE o.reservation_id = ?",
        Integer.class,
        reservationId);
  }

  private String orderId(MvcResult result) throws Exception {
    JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
    return body.path("id").asText();
  }

  private record OrderFixture(UUID userId, UUID reservationId) {}
}
