package com.portfolio.ticketing.order.api;

import com.portfolio.ticketing.common.api.PageResponse;
import com.portfolio.ticketing.order.service.OrderCancellationService;
import com.portfolio.ticketing.order.service.OrderCreationResult;
import com.portfolio.ticketing.order.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Orders", description = "Orders created from active reservations")
public class OrderController {

  private final OrderService orderService;
  private final OrderCancellationService orderCancellationService;

  public OrderController(
      OrderService orderService, OrderCancellationService orderCancellationService) {
    this.orderService = orderService;
    this.orderCancellationService = orderCancellationService;
  }

  @PostMapping
  @SecurityRequirement(name = "bearerAuth")
  @Operation(summary = "Create an order from an active reservation")
  @ApiResponse(responseCode = "201", description = "Order created")
  @ApiResponse(
      responseCode = "200",
      description = "Existing order returned for the same idempotency key")
  @ApiResponse(responseCode = "400", description = "Validation error or missing Idempotency-Key")
  @ApiResponse(responseCode = "401", description = "Authentication required")
  @ApiResponse(responseCode = "403", description = "USER role required")
  @ApiResponse(responseCode = "404", description = "Reservation not found")
  @ApiResponse(responseCode = "409", description = "Idempotency key used with another payload")
  @ApiResponse(responseCode = "422", description = "Reservation is not active")
  public ResponseEntity<OrderResponse> create(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody OrderRequest request) {
    OrderCreationResult result =
        orderService.create(UUID.fromString(jwt.getSubject()), idempotencyKey, request);
    URI location =
        ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{id}")
            .buildAndExpand(result.response().id())
            .toUri();
    return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
        .location(location)
        .body(result.response());
  }

  @GetMapping
  @SecurityRequirement(name = "bearerAuth")
  @Operation(summary = "List the authenticated user's orders")
  @ApiResponse(responseCode = "200", description = "Page of orders")
  public PageResponse<OrderResponse> list(
      @AuthenticationPrincipal Jwt jwt,
      @ParameterObject @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {
    return orderService.list(UUID.fromString(jwt.getSubject()), pageable);
  }

  @PostMapping("/{id}/cancel")
  @SecurityRequirement(name = "bearerAuth")
  @Operation(summary = "Cancel a pending order or refund a paid order before the event")
  @ApiResponse(responseCode = "200", description = "Order cancelled or refunded")
  @ApiResponse(responseCode = "401", description = "Authentication required")
  @ApiResponse(responseCode = "403", description = "USER role required")
  @ApiResponse(responseCode = "404", description = "Order not found")
  @ApiResponse(responseCode = "409", description = "Order is already in a final state")
  @ApiResponse(responseCode = "422", description = "The event has already started")
  public OrderResponse cancel(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
    return orderCancellationService.cancel(id, UUID.fromString(jwt.getSubject()));
  }

  @GetMapping("/{id}")
  @SecurityRequirement(name = "bearerAuth")
  @Operation(summary = "Get an order owned by the authenticated user")
  @ApiResponse(responseCode = "200", description = "Order found")
  @ApiResponse(responseCode = "404", description = "Order not found")
  public OrderResponse get(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
    return orderService.get(id, UUID.fromString(jwt.getSubject()));
  }
}
