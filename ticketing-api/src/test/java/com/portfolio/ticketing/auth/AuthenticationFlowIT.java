package com.portfolio.ticketing.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.ticketing.auth.api.LoginRequest;
import com.portfolio.ticketing.auth.api.RefreshTokenRequest;
import com.portfolio.ticketing.auth.api.TokenResponse;
import com.portfolio.ticketing.order.api.OrderRequest;
import com.portfolio.ticketing.order.api.OrderResponse;
import com.portfolio.ticketing.reservation.api.ReservationRequest;
import com.portfolio.ticketing.reservation.api.ReservationResponse;
import com.portfolio.ticketing.tickettype.api.TicketTypeRequest;
import com.portfolio.ticketing.tickettype.api.TicketTypeResponse;
import com.portfolio.ticketing.event.api.EventDetailResponse;
import com.portfolio.ticketing.event.api.EventRequest;
import com.portfolio.ticketing.support.AbstractIntegrationTest;
import com.portfolio.ticketing.user.domain.User;
import com.portfolio.ticketing.user.domain.UserRole;
import com.portfolio.ticketing.user.repository.UserRepository;
import com.portfolio.ticketing.venue.domain.Venue;
import com.portfolio.ticketing.venue.repository.VenueRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthenticationFlowIT extends AbstractIntegrationTest {

  @Autowired private TestRestTemplate restTemplate;
  @Autowired private UserRepository userRepository;
  @Autowired private VenueRepository venueRepository;
  @Autowired private PasswordEncoder passwordEncoder;

  @Test
  void should_authenticateAuthorizeRefreshAndRevoke_when_usingRealTokens() {
    String email = "organizer-" + UUID.randomUUID() + "@example.com";
    userRepository.save(
        new User(
            email, passwordEncoder.encode("Password123"), "Flow Organizer", UserRole.ORGANIZER));
    Venue venue = venueRepository.save(new Venue("Flow Arena", "Lima", "Main St 1", 500));

    ResponseEntity<TokenResponse> login =
        restTemplate.postForEntity(
            "/api/v1/auth/login", new LoginRequest(email, "Password123"), TokenResponse.class);

    assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
    TokenResponse tokens = login.getBody();
    assertThat(tokens).isNotNull();
    assertThat(tokens.accessToken()).isNotBlank();
    assertThat(tokens.refreshToken()).isNotBlank();

    EventRequest eventRequest = eventRequest(venue.getId(), "JWT Flow Event");
    ResponseEntity<EventDetailResponse> created =
        restTemplate.exchange(
            "/api/v1/events",
            HttpMethod.POST,
            new HttpEntity<>(eventRequest, bearerHeaders(tokens)),
            EventDetailResponse.class);

    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

    ResponseEntity<TokenResponse> refreshed =
        restTemplate.postForEntity(
            "/api/v1/auth/refresh",
            new RefreshTokenRequest(tokens.refreshToken()),
            TokenResponse.class);
    assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(refreshed.getBody().accessToken()).isNotBlank();

    ResponseEntity<Void> logout =
        restTemplate.postForEntity(
            "/api/v1/auth/logout", new RefreshTokenRequest(tokens.refreshToken()), Void.class);
    assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

    ResponseEntity<String> rejectedRefresh =
        restTemplate.postForEntity(
            "/api/v1/auth/refresh", new RefreshTokenRequest(tokens.refreshToken()), String.class);
    assertThat(rejectedRefresh.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void should_hideForeignOrganizerEventAsNotFound_usingTwoRealAccessTokens() {
    String ownerEmail = "owner-" + UUID.randomUUID() + "@example.com";
    String otherEmail = "other-" + UUID.randomUUID() + "@example.com";
    saveOrganizer(ownerEmail, "Owner");
    saveOrganizer(otherEmail, "Other");
    Venue venue = venueRepository.save(new Venue("Ownership Arena", "Lima", "Main St 2", 500));

    TokenResponse ownerTokens = login(ownerEmail);
    TokenResponse otherTokens = login(otherEmail);
    EventRequest initialRequest = eventRequest(venue.getId(), "Private Draft");

    ResponseEntity<EventDetailResponse> created =
        restTemplate.exchange(
            "/api/v1/events",
            HttpMethod.POST,
            new HttpEntity<>(initialRequest, bearerHeaders(ownerTokens)),
            EventDetailResponse.class);

    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    UUID eventId = created.getBody().id();

    EventRequest attemptedUpdate = eventRequest(venue.getId(), "Changed by another organizer");
    ResponseEntity<String> foreignUpdate =
        restTemplate.exchange(
            "/api/v1/events/" + eventId,
            HttpMethod.PUT,
            new HttpEntity<>(attemptedUpdate, bearerHeaders(otherTokens)),
            String.class);

    ResponseEntity<String> missingUpdate =
        restTemplate.exchange(
            "/api/v1/events/" + UUID.randomUUID(),
            HttpMethod.PUT,
            new HttpEntity<>(attemptedUpdate, bearerHeaders(otherTokens)),
            String.class);

    assertThat(foreignUpdate.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(missingUpdate.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

    ResponseEntity<EventDetailResponse> ownerUpdate =
        restTemplate.exchange(
            "/api/v1/events/" + eventId,
            HttpMethod.PUT,
            new HttpEntity<>(eventRequest(venue.getId(), "Updated by owner"), bearerHeaders(ownerTokens)),
            EventDetailResponse.class);

    assertThat(ownerUpdate.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(ownerUpdate.getBody().title()).isEqualTo("Updated by owner");
  }


  @Test
  void should_isolateReservationsAndOrdersBetweenTwoRealUserTokens() {
    String organizerEmail = "sales-owner-" + UUID.randomUUID() + "@example.com";
    String buyerAEmail = "buyer-a-" + UUID.randomUUID() + "@example.com";
    String buyerBEmail = "buyer-b-" + UUID.randomUUID() + "@example.com";
    saveOrganizer(organizerEmail, "Sales Owner");
    saveUser(buyerAEmail, "Buyer A");
    saveUser(buyerBEmail, "Buyer B");
    Venue venue = venueRepository.save(new Venue("Order Isolation Arena", "Lima", "Main St 3", 500));

    TokenResponse organizerTokens = login(organizerEmail);
    TokenResponse buyerATokens = login(buyerAEmail);
    TokenResponse buyerBTokens = login(buyerBEmail);

    ResponseEntity<EventDetailResponse> event =
        restTemplate.exchange(
            "/api/v1/events",
            HttpMethod.POST,
            new HttpEntity<>(eventRequest(venue.getId(), "Isolation Event"), bearerHeaders(organizerTokens)),
            EventDetailResponse.class);
    assertThat(event.getStatusCode()).isEqualTo(HttpStatus.CREATED);

    TicketTypeRequest ticketTypeRequest =
        new TicketTypeRequest(
            "General",
            new BigDecimal("30.00"),
            10,
            Instant.now().minusSeconds(60),
            Instant.now().plusSeconds(86400),
            4);
    ResponseEntity<TicketTypeResponse> ticketType =
        restTemplate.exchange(
            "/api/v1/events/" + event.getBody().id() + "/ticket-types",
            HttpMethod.POST,
            new HttpEntity<>(ticketTypeRequest, bearerHeaders(organizerTokens)),
            TicketTypeResponse.class);
    assertThat(ticketType.getStatusCode()).isEqualTo(HttpStatus.CREATED);

    ResponseEntity<EventDetailResponse> published =
        restTemplate.exchange(
            "/api/v1/events/" + event.getBody().id() + "/publish",
            HttpMethod.POST,
            new HttpEntity<>(bearerHeaders(organizerTokens)),
            EventDetailResponse.class);
    assertThat(published.getStatusCode()).isEqualTo(HttpStatus.OK);

    ResponseEntity<ReservationResponse> reservation =
        restTemplate.exchange(
            "/api/v1/reservations",
            HttpMethod.POST,
            new HttpEntity<>(
                new ReservationRequest(ticketType.getBody().id(), 1), bearerHeaders(buyerATokens)),
            ReservationResponse.class);
    assertThat(reservation.getStatusCode()).isEqualTo(HttpStatus.CREATED);

    ResponseEntity<OrderResponse> order =
        restTemplate.exchange(
            "/api/v1/orders",
            HttpMethod.POST,
            orderEntity(reservation.getBody().id(), buyerATokens),
            OrderResponse.class);
    assertThat(order.getStatusCode()).isEqualTo(HttpStatus.CREATED);

    ResponseEntity<String> foreignOrderRead =
        restTemplate.exchange(
            "/api/v1/orders/" + order.getBody().id(),
            HttpMethod.GET,
            new HttpEntity<>(bearerHeaders(buyerBTokens)),
            String.class);
    ResponseEntity<String> foreignOrderCancel =
        restTemplate.exchange(
            "/api/v1/orders/" + order.getBody().id() + "/cancel",
            HttpMethod.POST,
            new HttpEntity<>(bearerHeaders(buyerBTokens)),
            String.class);
    ResponseEntity<String> foreignReservationCancel =
        restTemplate.exchange(
            "/api/v1/reservations/" + reservation.getBody().id(),
            HttpMethod.DELETE,
            new HttpEntity<>(bearerHeaders(buyerBTokens)),
            String.class);

    assertThat(foreignOrderRead.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(foreignOrderCancel.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(foreignReservationCancel.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

    ResponseEntity<OrderResponse> ownerOrderRead =
        restTemplate.exchange(
            "/api/v1/orders/" + order.getBody().id(),
            HttpMethod.GET,
            new HttpEntity<>(bearerHeaders(buyerATokens)),
            OrderResponse.class);
    assertThat(ownerOrderRead.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  private void saveUser(String email, String fullName) {
    userRepository.save(
        new User(email, passwordEncoder.encode("Password123"), fullName, UserRole.USER));
  }

  private static HttpEntity<OrderRequest> orderEntity(UUID reservationId, TokenResponse tokens) {
    HttpHeaders headers = bearerHeaders(tokens);
    headers.set("Idempotency-Key", "checkout-" + UUID.randomUUID());
    return new HttpEntity<>(new OrderRequest(reservationId), headers);
  }

  private void saveOrganizer(String email, String fullName) {
    userRepository.save(
        new User(
            email, passwordEncoder.encode("Password123"), fullName, UserRole.ORGANIZER));
  }

  private TokenResponse login(String email) {
    ResponseEntity<TokenResponse> response =
        restTemplate.postForEntity(
            "/api/v1/auth/login", new LoginRequest(email, "Password123"), TokenResponse.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    return response.getBody();
  }

  private static EventRequest eventRequest(UUID venueId, String title) {
    return new EventRequest(
        venueId,
        title,
        "Created with a real access token",
        "MUSIC",
        Instant.parse("2099-01-01T20:00:00Z"),
        Instant.parse("2099-01-01T23:00:00Z"));
  }

  private static HttpHeaders bearerHeaders(TokenResponse tokens) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(tokens.accessToken());
    return headers;
  }
}
