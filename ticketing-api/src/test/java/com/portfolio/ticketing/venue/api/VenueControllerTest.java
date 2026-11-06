package com.portfolio.ticketing.venue.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portfolio.ticketing.auth.config.SecurityConfig;
import com.portfolio.ticketing.auth.config.SecurityErrorHandler;
import com.portfolio.ticketing.common.api.PageResponse;
import com.portfolio.ticketing.common.exception.ResourceNotFoundException;
import com.portfolio.ticketing.venue.service.VenueService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
    value = VenueController.class,
    properties = "ticketing.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")
@Import({SecurityConfig.class, SecurityErrorHandler.class})
@WithMockUser(roles = "ADMIN")
class VenueControllerTest {

  private static final UUID ID = UUID.fromString("7d4f1c9e-2b1a-4c3d-9e8f-0a1b2c3d4e5f");
  private static final VenueResponse RESPONSE =
      new VenueResponse(ID, "Arena", "Lima", "Main St 1", 500, Instant.EPOCH, Instant.EPOCH);
  private static final String VALID_BODY =
      """
      {"name":"Arena","city":"Lima","address":"Main St 1","capacity":500}
      """;

  @Autowired private MockMvc mockMvc;
  @MockitoBean private VenueService venueService;

  @Test
  void should_return201WithLocation_when_venueIsCreated() throws Exception {
    when(venueService.create(any(VenueRequest.class))).thenReturn(RESPONSE);

    mockMvc
        .perform(post("/api/v1/venues").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", "http://localhost/api/v1/venues/" + ID));
  }

  @Test
  void should_return400WithFieldErrors_when_bodyIsInvalid() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/venues")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\",\"city\":\"Lima\",\"address\":\"x\",\"capacity\":0}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.capacity").value("capacity must be positive"));
  }

  @Test
  void should_return404_when_venueDoesNotExist() throws Exception {
    when(venueService.get(ID)).thenThrow(new ResourceNotFoundException("Venue not found"));

    mockMvc.perform(get("/api/v1/venues/" + ID)).andExpect(status().isNotFound());
  }

  @Test
  void should_returnPageResponse_when_listingVenues() throws Exception {
    when(venueService.list(any(Pageable.class)))
        .thenReturn(new PageResponse<>(List.of(RESPONSE), 0, 20, 1, 1));

    mockMvc
        .perform(get("/api/v1/venues"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].name").value("Arena"))
        .andExpect(jsonPath("$.totalElements").value(1));
  }

  @Test
  void should_return204_when_venueIsDeleted() throws Exception {
    mockMvc.perform(delete("/api/v1/venues/" + ID)).andExpect(status().isNoContent());

    verify(venueService).delete(eq(ID));
  }
}
