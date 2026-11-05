package com.portfolio.ticketing.event.api;

import com.portfolio.ticketing.common.api.PageResponse;
import com.portfolio.ticketing.event.repository.EventFilter;
import com.portfolio.ticketing.event.service.EventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/events")
@Tag(name = "Events", description = "Public event catalog and organizer management")
public class EventController {

  private final EventService eventService;

  public EventController(EventService eventService) {
    this.eventService = eventService;
  }

  @GetMapping
  @Operation(summary = "Search published events (paginated)")
  @ApiResponse(responseCode = "200", description = "Page of published events")
  public PageResponse<EventSummaryResponse> list(
      @Parameter(description = "Text contained in the title") @RequestParam(required = false)
          String q,
      @RequestParam(required = false) String category,
      @RequestParam(required = false) String city,
      @Parameter(description = "Events starting at or after this instant (ISO-8601)")
          @RequestParam(required = false)
          Instant from,
      @Parameter(description = "Events starting before this instant (ISO-8601)")
          @RequestParam(required = false)
          Instant to,
      @ParameterObject @PageableDefault(size = 20, sort = "startsAt") Pageable pageable) {
    return eventService.listPublished(new EventFilter(q, category, city, from, to), pageable);
  }

  @GetMapping("/{id}")
  @Operation(summary = "Get a published event with its ticket types")
  @ApiResponse(responseCode = "200", description = "Event found")
  @ApiResponse(responseCode = "404", description = "Event not found or not published")
  public EventDetailResponse get(@PathVariable UUID id) {
    return eventService.getPublished(id);
  }

  // Phase 2 replaces the X-User-Id header with the authenticated principal from the JWT.
  @PostMapping
  @Operation(summary = "Create an event in DRAFT status")
  @ApiResponse(responseCode = "201", description = "Event created")
  @ApiResponse(responseCode = "400", description = "Validation error")
  @ApiResponse(responseCode = "404", description = "Venue not found")
  @ApiResponse(responseCode = "422", description = "Event dates are inconsistent")
  public ResponseEntity<EventDetailResponse> create(
      @Parameter(description = "Organizer id (temporary until JWT authentication)")
          @RequestHeader("X-User-Id")
          UUID organizerId,
      @Valid @RequestBody EventRequest request) {
    EventDetailResponse created = eventService.create(organizerId, request);
    URI location =
        ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{id}")
            .buildAndExpand(created.id())
            .toUri();
    return ResponseEntity.created(location).body(created);
  }

  @PutMapping("/{id}")
  @Operation(summary = "Update an event")
  @ApiResponse(responseCode = "200", description = "Event updated")
  @ApiResponse(responseCode = "400", description = "Validation error")
  @ApiResponse(responseCode = "404", description = "Event or venue not found")
  @ApiResponse(responseCode = "422", description = "Event cannot be edited in its status")
  public EventDetailResponse update(
      @PathVariable UUID id, @Valid @RequestBody EventRequest request) {
    return eventService.update(id, request);
  }

  @PostMapping("/{id}/publish")
  @Operation(summary = "Publish an event (requires at least one ticket type)")
  @ApiResponse(responseCode = "200", description = "Event published")
  @ApiResponse(responseCode = "404", description = "Event not found")
  @ApiResponse(responseCode = "409", description = "Invalid status transition")
  @ApiResponse(responseCode = "422", description = "Event has no ticket types")
  public EventDetailResponse publish(@PathVariable UUID id) {
    return eventService.publish(id);
  }

  @PostMapping("/{id}/cancel")
  @Operation(summary = "Cancel an event")
  @ApiResponse(responseCode = "200", description = "Event cancelled")
  @ApiResponse(responseCode = "404", description = "Event not found")
  @ApiResponse(responseCode = "409", description = "Invalid status transition")
  public EventDetailResponse cancel(@PathVariable UUID id) {
    return eventService.cancel(id);
  }
}
