package com.portfolio.ticketing.tickettype.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portfolio.ticketing.auth.config.SecurityConfig;
import com.portfolio.ticketing.auth.config.SecurityErrorHandler;
import com.portfolio.ticketing.common.exception.BusinessRuleViolationException;
import com.portfolio.ticketing.common.exception.DuplicateResourceException;
import com.portfolio.ticketing.tickettype.service.TicketTypeService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
    value = TicketTypeController.class,
    properties = "ticketing.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")
@Import({SecurityConfig.class, SecurityErrorHandler.class})
@WithMockUser(roles = "ORGANIZER")
class TicketTypeControllerTest {

  private static final UUID EVENT_ID = UUID.fromString("0b5e6f1a-3c2d-4e5f-8a9b-1c2d3e4f5a6b");
  private static final UUID ID = UUID.fromString("1c6f7a2b-4d3e-4f60-9bac-2d3e4f5a6b7c");
  private static final Instant SALE = Instant.parse("2099-01-01T00:00:00Z");
  private static final String VALID_BODY =
      """
      {"name":"VIP","price":120.00,"totalQuantity":100,
       "saleStartsAt":"2099-01-01T00:00:00Z","saleEndsAt":"2099-02-01T00:00:00Z",
       "maxPerOrder":4}
      """;

  @Autowired private MockMvc mockMvc;
  @MockitoBean private TicketTypeService ticketTypeService;

  private static TicketTypeResponse response() {
    return new TicketTypeResponse(
        ID, "VIP", new BigDecimal("120.00"), "USD", 100, 100, SALE, SALE, 4);
  }

  @Test
  void should_return201WithLocation_when_creatingValidTicketType() throws Exception {
    when(ticketTypeService.create(eq(EVENT_ID), any(TicketTypeRequest.class)))
        .thenReturn(response());

    mockMvc
        .perform(
            post("/api/v1/events/" + EVENT_ID + "/ticket-types")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", "http://localhost/api/v1/ticket-types/" + ID))
        .andExpect(jsonPath("$.currency").value("USD"))
        .andExpect(jsonPath("$.availableQuantity").value(100));
  }

  @Test
  @WithMockUser(roles = "USER")
  void should_return403_when_userRoleCreatesTicketType() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/events/" + EVENT_ID + "/ticket-types")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
        .andExpect(status().isForbidden());

    verifyNoInteractions(ticketTypeService);
  }

  @Test
  @WithAnonymousUser
  void should_return401_when_ticketTypeMutationIsAnonymous() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/events/" + EVENT_ID + "/ticket-types")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
        .andExpect(status().isUnauthorized());

    verifyNoInteractions(ticketTypeService);
  }

  @Test
  void should_return400_when_ticketTypeRequestIsInvalid() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/events/" + EVENT_ID + "/ticket-types")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\",\"price\":-1,\"totalQuantity\":0}"))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(ticketTypeService);
  }

  @Test
  void should_return409_when_nameIsDuplicated() throws Exception {
    when(ticketTypeService.create(eq(EVENT_ID), any(TicketTypeRequest.class)))
        .thenThrow(new DuplicateResourceException("duplicated"));

    mockMvc
        .perform(
            post("/api/v1/events/" + EVENT_ID + "/ticket-types")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
        .andExpect(status().isConflict());
  }

  @Test
  void should_return200_when_updatingTicketType() throws Exception {
    when(ticketTypeService.update(eq(ID), any(TicketTypeRequest.class))).thenReturn(response());

    mockMvc
        .perform(
            put("/api/v1/ticket-types/" + ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("VIP"));
  }

  @Test
  void should_return422_when_totalDropsBelowSoldOrReserved() throws Exception {
    when(ticketTypeService.update(eq(ID), any(TicketTypeRequest.class)))
        .thenThrow(new BusinessRuleViolationException("below sold"));

    mockMvc
        .perform(
            put("/api/v1/ticket-types/" + ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
        .andExpect(status().isUnprocessableEntity());
  }
}
