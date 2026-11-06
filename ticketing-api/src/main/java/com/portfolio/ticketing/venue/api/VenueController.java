package com.portfolio.ticketing.venue.api;

import com.portfolio.ticketing.common.api.PageResponse;
import com.portfolio.ticketing.venue.service.VenueService;
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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/venues")
@Tag(name = "Venues", description = "Venue management (read is public, write requires ADMIN)")
public class VenueController {

  private final VenueService venueService;

  public VenueController(VenueService venueService) {
    this.venueService = venueService;
  }

  @GetMapping
  @Operation(summary = "List venues (paginated)")
  @ApiResponse(responseCode = "200", description = "Page of venues")
  public PageResponse<VenueResponse> list(
      @ParameterObject @PageableDefault(size = 20, sort = "name") Pageable pageable) {
    return venueService.list(pageable);
  }

  @GetMapping("/{id}")
  @Operation(summary = "Get a venue by id")
  @ApiResponse(responseCode = "200", description = "Venue found")
  @ApiResponse(responseCode = "404", description = "Venue not found")
  public VenueResponse get(@PathVariable UUID id) {
    return venueService.get(id);
  }

  @PostMapping
  @SecurityRequirement(name = "bearerAuth")
  @Operation(summary = "Create a venue")
  @ApiResponse(responseCode = "201", description = "Venue created")
  @ApiResponse(responseCode = "400", description = "Validation error")
  public ResponseEntity<VenueResponse> create(@Valid @RequestBody VenueRequest request) {
    VenueResponse created = venueService.create(request);
    URI location =
        ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{id}")
            .buildAndExpand(created.id())
            .toUri();
    return ResponseEntity.created(location).body(created);
  }

  @PutMapping("/{id}")
  @SecurityRequirement(name = "bearerAuth")
  @Operation(summary = "Update a venue")
  @ApiResponse(responseCode = "200", description = "Venue updated")
  @ApiResponse(responseCode = "400", description = "Validation error")
  @ApiResponse(responseCode = "404", description = "Venue not found")
  public VenueResponse update(@PathVariable UUID id, @Valid @RequestBody VenueRequest request) {
    return venueService.update(id, request);
  }

  @DeleteMapping("/{id}")
  @SecurityRequirement(name = "bearerAuth")
  @Operation(summary = "Delete a venue")
  @ApiResponse(responseCode = "204", description = "Venue deleted")
  @ApiResponse(responseCode = "404", description = "Venue not found")
  @ApiResponse(responseCode = "409", description = "Venue is referenced by events")
  public ResponseEntity<Void> delete(@PathVariable UUID id) {
    venueService.delete(id);
    return ResponseEntity.noContent().build();
  }
}
