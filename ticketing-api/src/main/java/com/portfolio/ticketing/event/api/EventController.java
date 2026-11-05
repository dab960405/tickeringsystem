package com.portfolio.ticketing.event.api;

import com.portfolio.ticketing.common.api.PageResponse;
import com.portfolio.ticketing.event.repository.EventFilter;
import com.portfolio.ticketing.event.service.EventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.UUID;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/events")
@Tag(name = "Events", description = "Public event catalog")
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
}
