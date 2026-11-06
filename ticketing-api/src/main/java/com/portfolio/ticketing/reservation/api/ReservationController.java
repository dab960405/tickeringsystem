package com.portfolio.ticketing.reservation.api;

import com.portfolio.ticketing.reservation.service.ReservationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/reservations")
@Tag(name = "Reservations", description = "Temporary ticket reservations for authenticated users")
public class ReservationController {

  private final ReservationService reservationService;

  public ReservationController(ReservationService reservationService) {
    this.reservationService = reservationService;
  }

  @PostMapping
  @SecurityRequirement(name = "bearerAuth")
  @Operation(summary = "Reserve tickets temporarily for ten minutes by default")
  @ApiResponse(responseCode = "201", description = "Reservation created")
  @ApiResponse(responseCode = "400", description = "Validation error")
  @ApiResponse(responseCode = "401", description = "Authentication required")
  @ApiResponse(responseCode = "403", description = "USER role required")
  @ApiResponse(responseCode = "404", description = "Ticket type not found")
  @ApiResponse(responseCode = "409", description = "Insufficient stock")
  @ApiResponse(responseCode = "422", description = "Event or sale window is not available")
  public ResponseEntity<ReservationResponse> create(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ReservationRequest request) {
    ReservationResponse created =
        reservationService.create(UUID.fromString(jwt.getSubject()), request);
    URI location =
        ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{id}")
            .buildAndExpand(created.id())
            .toUri();
    return ResponseEntity.created(location).body(created);
  }

  @DeleteMapping("/{id}")
  @SecurityRequirement(name = "bearerAuth")
  @Operation(summary = "Cancel an active reservation and return its stock")
  @ApiResponse(responseCode = "204", description = "Reservation cancelled")
  @ApiResponse(responseCode = "401", description = "Authentication required")
  @ApiResponse(responseCode = "403", description = "USER role required")
  @ApiResponse(responseCode = "404", description = "Reservation not found")
  @ApiResponse(responseCode = "409", description = "Reservation is no longer active")
  public ResponseEntity<Void> cancel(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
    reservationService.cancel(id, UUID.fromString(jwt.getSubject()));
    return ResponseEntity.noContent().build();
  }
}
