package com.portfolio.ticketing.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.ticketing.auth.api.LoginRequest;
import com.portfolio.ticketing.auth.api.RefreshTokenRequest;
import com.portfolio.ticketing.auth.api.TokenResponse;
import com.portfolio.ticketing.event.api.EventRequest;
import com.portfolio.ticketing.support.AbstractIntegrationTest;
import com.portfolio.ticketing.user.domain.User;
import com.portfolio.ticketing.user.domain.UserRole;
import com.portfolio.ticketing.user.repository.UserRepository;
import com.portfolio.ticketing.venue.domain.Venue;
import com.portfolio.ticketing.venue.repository.VenueRepository;
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

    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(tokens.accessToken());
    EventRequest eventRequest =
        new EventRequest(
            venue.getId(),
            "JWT Flow Event",
            "Created with a real access token",
            "MUSIC",
            Instant.parse("2099-01-01T20:00:00Z"),
            Instant.parse("2099-01-01T23:00:00Z"));
    ResponseEntity<String> created =
        restTemplate.exchange(
            "/api/v1/events",
            HttpMethod.POST,
            new HttpEntity<>(eventRequest, headers),
            String.class);

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
}
