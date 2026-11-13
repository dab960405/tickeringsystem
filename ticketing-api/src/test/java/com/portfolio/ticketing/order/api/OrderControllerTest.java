package com.portfolio.ticketing.order.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portfolio.ticketing.auth.config.SecurityConfig;
import com.portfolio.ticketing.auth.config.SecurityErrorHandler;
import com.portfolio.ticketing.common.api.PageResponse;
import com.portfolio.ticketing.order.domain.FulfillmentStatus;
import com.portfolio.ticketing.order.domain.OrderStatus;
import com.portfolio.ticketing.order.domain.RefundStatus;
import com.portfolio.ticketing.order.service.OrderCancellationService;
import com.portfolio.ticketing.order.service.OrderCreationResult;
import com.portfolio.ticketing.order.service.OrderService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(
    value = OrderController.class,
    properties = "ticketing.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")
@Import({SecurityConfig.class, SecurityErrorHandler.class})
class OrderControllerTest {

  private static final UUID USER_ID = UUID.fromString("0b5e6f1a-3c2d-4e5f-8a9b-1c2d3e4f5a6b");
  private static final UUID ORDER_ID = UUID.fromString("2d7a8b3c-5e4f-6a70-8b9c-1d2e3f4a5b6c");
  private static final UUID RESERVATION_ID =
      UUID.fromString("1c6f7a2b-4d3e-4f60-9bac-2d3e4f5a6b7c");
  private static final UUID TICKET_TYPE_ID = UUID.randomUUID();
  private static final Instant CREATED_AT = Instant.parse("2099-01-01T00:00:00Z");
  private static final String VALID_BODY = "{\"reservationId\":\"" + RESERVATION_ID + "\"}";

  @Autowired private MockMvc mockMvc;
  @MockitoBean private OrderService orderService;
  @MockitoBean private OrderCancellationService orderCancellationService;

  private static RequestPostProcessor userJwt() {
    return jwt()
        .jwt(token -> token.subject(USER_ID.toString()).claim("role", "USER"))
        .authorities(new SimpleGrantedAuthority("ROLE_USER"));
  }

  private static OrderResponse response() {
    return new OrderResponse(
        ORDER_ID,
        RESERVATION_ID,
        OrderStatus.PENDING_PAYMENT,
        FulfillmentStatus.PENDING,
        RefundStatus.NONE,
        new BigDecimal("80.00"),
        "USD",
        List.of(
            new OrderItemResponse(
                TICKET_TYPE_ID, 2, new BigDecimal("40.00"), "USD", new BigDecimal("80.00"))),
        CREATED_AT);
  }

  @Test
  void should_return201WithLocation_when_orderIsCreated() throws Exception {
    when(orderService.create(eq(USER_ID), eq("checkout-123"), any(OrderRequest.class)))
        .thenReturn(new OrderCreationResult(response(), true));

    mockMvc
        .perform(
            post("/api/v1/orders")
                .with(userJwt())
                .header("Idempotency-Key", "checkout-123")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", "http://localhost/api/v1/orders/" + ORDER_ID))
        .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
        .andExpect(jsonPath("$.totalAmount").value(80.0));
  }

  @Test
  void should_return200_when_idempotencyKeyWasAlreadyUsed() throws Exception {
    when(orderService.create(eq(USER_ID), eq("checkout-123"), any(OrderRequest.class)))
        .thenReturn(new OrderCreationResult(response(), false));

    mockMvc
        .perform(
            post("/api/v1/orders")
                .with(userJwt())
                .header("Idempotency-Key", "checkout-123")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(ORDER_ID.toString()));
  }

  @Test
  void should_return400_when_reservationIdIsMissing() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/orders")
                .with(userJwt())
                .header("Idempotency-Key", "checkout-123")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(orderService);
  }

  @Test
  void should_return400_when_idempotencyHeaderIsMissing() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/orders")
                .with(userJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(orderService);
  }

  @Test
  @WithAnonymousUser
  void should_return401_when_orderIsAnonymous() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/orders")
                .header("Idempotency-Key", "checkout-123")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
        .andExpect(status().isUnauthorized());

    verifyNoInteractions(orderService);
  }

  @Test
  @WithMockUser(roles = "ORGANIZER")
  void should_return403_when_nonUserRoleCreatesOrder() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/orders")
                .header("Idempotency-Key", "checkout-123")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
        .andExpect(status().isForbidden());

    verifyNoInteractions(orderService);
  }

  @Test
  void should_returnOrderHistory() throws Exception {
    when(orderService.list(eq(USER_ID), any(Pageable.class)))
        .thenReturn(new PageResponse<>(List.of(response()), 0, 20, 1, 1));

    mockMvc
        .perform(get("/api/v1/orders").with(userJwt()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].id").value(ORDER_ID.toString()))
        .andExpect(jsonPath("$.totalElements").value(1));
  }

  @Test
  void should_returnUpdatedOrder_when_orderIsCancelled() throws Exception {
    when(orderCancellationService.cancel(ORDER_ID, USER_ID)).thenReturn(response());

    mockMvc
        .perform(post("/api/v1/orders/" + ORDER_ID + "/cancel").with(userJwt()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(ORDER_ID.toString()));
  }

  @Test
  void should_returnOwnedOrderDetail() throws Exception {
    when(orderService.get(ORDER_ID, USER_ID)).thenReturn(response());

    mockMvc
        .perform(get("/api/v1/orders/" + ORDER_ID).with(userJwt()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reservationId").value(RESERVATION_ID.toString()));
  }
}
