package com.portfolio.ticketing.reservation.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portfolio.ticketing.auth.config.SecurityConfig;
import com.portfolio.ticketing.auth.config.SecurityErrorHandler;
import com.portfolio.ticketing.common.exception.InsufficientStockException;
import com.portfolio.ticketing.common.exception.ResourceNotFoundException;
import com.portfolio.ticketing.reservation.domain.ReservationStatus;
import com.portfolio.ticketing.reservation.service.ReservationService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
    value = ReservationController.class,
    properties = "ticketing.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")
@Import({SecurityConfig.class, SecurityErrorHandler.class})
class ReservationControllerTest {

  private static final UUID USER_ID = UUID.fromString("0b5e6f1a-3c2d-4e5f-8a9b-1c2d3e4f5a6b");
  private static final UUID TICKET_TYPE_ID =
      UUID.fromString("1c6f7a2b-4d3e-4f60-9bac-2d3e4f5a6b7c");
  private static final UUID RESERVATION_ID =
      UUID.fromString("2d7a8b3c-5e4f-6a70-8b9c-1d2e3f4a5b6c");
  private static final Instant EXPIRES_AT = Instant.parse("2099-01-01T00:10:00Z");
  private static final String VALID_BODY =
      "{\"ticketTypeId\":\"" + TICKET_TYPE_ID + "\",\"quantity\":2}";

  @Autowired private MockMvc mockMvc;
  @MockitoBean private ReservationService reservationService;

  private static ReservationResponse response() {
    return new ReservationResponse(
        RESERVATION_ID, TICKET_TYPE_ID, 2, ReservationStatus.ACTIVE, EXPIRES_AT);
  }

  private static org.springframework.test.web.servlet.request.RequestPostProcessor userJwt() {
    return jwt()
        .jwt(token -> token.subject(USER_ID.toString()).claim("role", "USER"))
        .authorities(new SimpleGrantedAuthority("ROLE_USER"));
  }

  @Test
  void should_return201WithLocation_when_creatingValidReservation() throws Exception {
    when(reservationService.create(eq(USER_ID), any(ReservationRequest.class)))
        .thenReturn(response());

    mockMvc
        .perform(
            post("/api/v1/reservations")
                .with(userJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
        .andExpect(status().isCreated())
        .andExpect(
            header().string("Location", "http://localhost/api/v1/reservations/" + RESERVATION_ID))
        .andExpect(jsonPath("$.status").value("ACTIVE"))
        .andExpect(jsonPath("$.quantity").value(2));
  }

  @Test
  void should_return400_when_reservationRequestIsInvalid() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/reservations")
                .with(userJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\":0}"))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(reservationService);
  }

  @Test
  @WithAnonymousUser
  void should_return401_when_reservationIsAnonymous() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/reservations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
        .andExpect(status().isUnauthorized());

    verifyNoInteractions(reservationService);
  }

  @Test
  @WithMockUser(roles = "ORGANIZER")
  void should_return403_when_nonUserRoleCreatesReservation() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/reservations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
        .andExpect(status().isForbidden());

    verifyNoInteractions(reservationService);
  }

  @Test
  void should_return409_when_stockIsInsufficient() throws Exception {
    when(reservationService.create(eq(USER_ID), any(ReservationRequest.class)))
        .thenThrow(new InsufficientStockException("out of stock"));

    mockMvc
        .perform(
            post("/api/v1/reservations")
                .with(userJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
        .andExpect(status().isConflict());
  }

  @Test
  void should_return204_when_ownerCancelsReservation() throws Exception {
    mockMvc
        .perform(delete("/api/v1/reservations/" + RESERVATION_ID).with(userJwt()))
        .andExpect(status().isNoContent());

    verify(reservationService).cancel(RESERVATION_ID, USER_ID);
  }

  @Test
  void should_return404_when_reservationIsNotOwned() throws Exception {
    doThrow(new ResourceNotFoundException("not found"))
        .when(reservationService)
        .cancel(RESERVATION_ID, USER_ID);

    mockMvc
        .perform(delete("/api/v1/reservations/" + RESERVATION_ID).with(userJwt()))
        .andExpect(status().isNotFound());
  }
}
