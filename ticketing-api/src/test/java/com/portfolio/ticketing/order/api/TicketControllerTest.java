package com.portfolio.ticketing.order.api;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portfolio.ticketing.auth.config.SecurityConfig;
import com.portfolio.ticketing.auth.config.SecurityErrorHandler;
import com.portfolio.ticketing.order.domain.TicketStatus;
import com.portfolio.ticketing.order.service.TicketService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(
    value = TicketController.class,
    properties = "ticketing.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")
@Import({SecurityConfig.class, SecurityErrorHandler.class})
class TicketControllerTest {

  private static final UUID USER_ID = UUID.fromString("0b5e6f1a-3c2d-4e5f-8a9b-1c2d3e4f5a6b");
  private static final UUID ORDER_ID = UUID.fromString("2d7a8b3c-5e4f-6a70-8b9c-1d2e3f4a5b6c");
  private static final UUID TICKET_TYPE_ID = UUID.randomUUID();

  @Autowired private MockMvc mockMvc;
  @MockitoBean private TicketService ticketService;

  private static RequestPostProcessor userJwt() {
    return jwt()
        .jwt(token -> token.subject(USER_ID.toString()).claim("role", "USER"))
        .authorities(new SimpleGrantedAuthority("ROLE_USER"));
  }

  @Test
  void should_returnValidTicketsOwnedByUser() throws Exception {
    when(ticketService.listValidForUser(USER_ID))
        .thenReturn(
            List.of(
                new TicketResponse("ticket-code", ORDER_ID, TICKET_TYPE_ID, TicketStatus.VALID)));

    mockMvc
        .perform(get("/api/v1/me/tickets").with(userJwt()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].code").value("ticket-code"))
        .andExpect(jsonPath("$[0].orderId").value(ORDER_ID.toString()))
        .andExpect(jsonPath("$[0].status").value("VALID"));
  }

  @Test
  @WithAnonymousUser
  void should_return401_when_ticketsAreRequestedAnonymously() throws Exception {
    mockMvc.perform(get("/api/v1/me/tickets")).andExpect(status().isUnauthorized());

    verifyNoInteractions(ticketService);
  }

  @Test
  @WithMockUser(roles = "ORGANIZER")
  void should_return403_when_nonUserRoleRequestsTickets() throws Exception {
    mockMvc.perform(get("/api/v1/me/tickets")).andExpect(status().isForbidden());

    verifyNoInteractions(ticketService);
  }
}
