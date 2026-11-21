package com.portfolio.ticketing.tickettype.api;

import com.portfolio.ticketing.tickettype.service.TicketTypeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Ticket types", description = "Ticket types of an event (owner or ADMIN)")
public class TicketTypeController {

  private final TicketTypeService ticketTypeService;

  public TicketTypeController(TicketTypeService ticketTypeService) {
    this.ticketTypeService = ticketTypeService;
  }

  @PostMapping("/events/{eventId}/ticket-types")
  @SecurityRequirement(name = "bearerAuth")
  @Operation(summary = "Create a ticket type for an event")
  @ApiResponse(responseCode = "201", description = "Ticket type created")
  @ApiResponse(responseCode = "400", description = "Validation error")
  @ApiResponse(responseCode = "404", description = "Event not found")
  @ApiResponse(responseCode = "409", description = "Name already used in this event")
  @ApiResponse(responseCode = "422", description = "Event not editable or invalid sale window")
  public ResponseEntity<TicketTypeResponse> create(
      @PathVariable UUID eventId, @Valid @RequestBody TicketTypeRequest request) {
    TicketTypeResponse created = ticketTypeService.create(eventId, request);
    URI location =
        ServletUriComponentsBuilder.fromCurrentContextPath()
            .path("/api/v1/ticket-types/{id}")
            .buildAndExpand(created.id())
            .toUri();
    return ResponseEntity.created(location).body(created);
  }

  @PutMapping("/ticket-types/{id}")
  @SecurityRequirement(name = "bearerAuth")
  @Operation(summary = "Update a ticket type (total cannot drop below sold or reserved)")
  @ApiResponse(responseCode = "200", description = "Ticket type updated")
  @ApiResponse(responseCode = "400", description = "Validation error")
  @ApiResponse(responseCode = "404", description = "Ticket type not found")
  @ApiResponse(responseCode = "409", description = "Name already used or concurrent edit")
  @ApiResponse(responseCode = "422", description = "Total below sold or reserved quantity")
  public TicketTypeResponse update(
      @PathVariable UUID id, @Valid @RequestBody TicketTypeRequest request) {
    return ticketTypeService.update(id, request);
  }
}
