package com.portfolio.ticketing.event.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portfolio.ticketing.common.api.PageResponse;
import com.portfolio.ticketing.common.exception.ResourceNotFoundException;
import com.portfolio.ticketing.event.domain.EventStatus;
import com.portfolio.ticketing.event.repository.EventFilter;
import com.portfolio.ticketing.event.service.EventService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(EventController.class)
class EventControllerTest {

  private static final UUID ID = UUID.fromString("0b5e6f1a-3c2d-4e5f-8a9b-1c2d3e4f5a6b");
  private static final Instant FROM = Instant.parse("2030-01-01T00:00:00Z");

  @Autowired private MockMvc mockMvc;
  @MockitoBean private EventService eventService;

  @Test
  void should_passFiltersToService_when_searchingCatalog() throws Exception {
    EventSummaryResponse summary =
        new EventSummaryResponse(
            ID, "Show", "MUSIC", FROM, FROM, EventStatus.PUBLISHED, ID, "Arena", "Lima");
    when(eventService.listPublished(any(EventFilter.class), any(Pageable.class)))
        .thenReturn(new PageResponse<>(List.of(summary), 0, 20, 1, 1));

    mockMvc
        .perform(
            get("/api/v1/events")
                .param("q", "show")
                .param("city", "Lima")
                .param("from", "2030-01-01T00:00:00Z"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].city").value("Lima"))
        .andExpect(jsonPath("$.totalElements").value(1));

    verify(eventService)
        .listPublished(eq(new EventFilter("show", null, "Lima", FROM, null)), any(Pageable.class));
  }

  @Test
  void should_return404_when_eventIsNotPublished() throws Exception {
    when(eventService.getPublished(ID)).thenThrow(new ResourceNotFoundException("not found"));

    mockMvc.perform(get("/api/v1/events/" + ID)).andExpect(status().isNotFound());
  }
}
