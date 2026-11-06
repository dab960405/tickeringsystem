package com.portfolio.ticketing.event.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portfolio.ticketing.auth.config.SecurityConfig;
import com.portfolio.ticketing.auth.config.SecurityErrorHandler;
import com.portfolio.ticketing.common.api.PageResponse;
import com.portfolio.ticketing.common.exception.BusinessRuleViolationException;
import com.portfolio.ticketing.common.exception.InvalidStateTransitionException;
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
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
    value = EventController.class,
    properties = "ticketing.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")
@Import({SecurityConfig.class, SecurityErrorHandler.class})
@WithMockUser(roles = "ORGANIZER")
class EventControllerTest {

  private static final UUID ID = UUID.fromString("0b5e6f1a-3c2d-4e5f-8a9b-1c2d3e4f5a6b");
  private static final Instant FROM = Instant.parse("2030-01-01T00:00:00Z");

  @Autowired private MockMvc mockMvc;
  @MockitoBean private EventService eventService;

  @Test
  @WithAnonymousUser
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

  private static final String VALID_BODY =
      """
      {"venueId":"%s","title":"Show","description":"Live","category":"MUSIC",
       "startsAt":"2099-01-01T20:00:00Z","endsAt":"2099-01-01T23:00:00Z"}
      """
          .formatted(ID);

  private static EventDetailResponse detail(EventStatus status) {
    return new EventDetailResponse(
        ID, ID, "Show", "Live", "MUSIC", FROM, FROM, status, null, List.of(), FROM, FROM);
  }

  @Test
  void should_return201WithLocation_when_creatingValidEvent() throws Exception {
    when(eventService.create(eq(ID), any(EventRequest.class)))
        .thenReturn(detail(EventStatus.DRAFT));

    mockMvc
        .perform(
            post("/api/v1/events")
                .with(
                    jwt()
                        .jwt(token -> token.subject(ID.toString()).claim("role", "ORGANIZER"))
                        .authorities(new SimpleGrantedAuthority("ROLE_ORGANIZER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", "http://localhost/api/v1/events/" + ID))
        .andExpect(jsonPath("$.status").value("DRAFT"));
  }

  @Test
  void should_return400_when_eventRequestIsInvalid() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/events")
                .with(
                    jwt()
                        .jwt(token -> token.subject(ID.toString()).claim("role", "ORGANIZER"))
                        .authorities(new SimpleGrantedAuthority("ROLE_ORGANIZER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"\"}"))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(eventService);
  }

  @Test
  @WithAnonymousUser
  void should_return401_when_organizerTokenIsMissing() throws Exception {
    mockMvc
        .perform(post("/api/v1/events").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
        .andExpect(status().isUnauthorized());

    verifyNoInteractions(eventService);
  }

  @Test
  @WithMockUser(roles = "USER")
  void should_return403_when_userRoleCreatesEvent() throws Exception {
    mockMvc
        .perform(post("/api/v1/events").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
        .andExpect(status().isForbidden());

    verifyNoInteractions(eventService);
  }

  @Test
  void should_return200_when_updatingEvent() throws Exception {
    when(eventService.update(eq(ID), any(EventRequest.class)))
        .thenReturn(detail(EventStatus.DRAFT));

    mockMvc
        .perform(
            put("/api/v1/events/" + ID).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(ID.toString()));
  }

  @Test
  void should_return200_when_publishingEvent() throws Exception {
    when(eventService.publish(ID)).thenReturn(detail(EventStatus.PUBLISHED));

    mockMvc
        .perform(post("/api/v1/events/" + ID + "/publish"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PUBLISHED"));
  }

  @Test
  void should_return422_when_publishingEventWithoutTicketTypes() throws Exception {
    when(eventService.publish(ID))
        .thenThrow(new BusinessRuleViolationException("needs a ticket type"));

    mockMvc
        .perform(post("/api/v1/events/" + ID + "/publish"))
        .andExpect(status().isUnprocessableEntity());
  }

  @Test
  void should_return409_when_cancellingEventInFinalStatus() throws Exception {
    when(eventService.cancel(ID)).thenThrow(new InvalidStateTransitionException("final"));

    mockMvc.perform(post("/api/v1/events/" + ID + "/cancel")).andExpect(status().isConflict());
  }
}
