package com.portfolio.ticketing.order.api;

import com.portfolio.ticketing.order.service.TicketService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me/tickets")
@Tag(name = "Tickets", description = "Tickets owned by the authenticated user")
public class TicketController {

  private final TicketService ticketService;

  public TicketController(TicketService ticketService) {
    this.ticketService = ticketService;
  }

  @GetMapping
  @SecurityRequirement(name = "bearerAuth")
  @Operation(summary = "List the authenticated user's valid tickets")
  @ApiResponse(responseCode = "200", description = "Valid tickets returned")
  @ApiResponse(responseCode = "401", description = "Authentication required")
  @ApiResponse(responseCode = "403", description = "USER role required")
  public List<TicketResponse> list(@AuthenticationPrincipal Jwt jwt) {
    return ticketService.listValidForUser(UUID.fromString(jwt.getSubject()));
  }
}
