package com.portfolio.ticketing.event.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.portfolio.ticketing.event.domain.Event;
import com.portfolio.ticketing.common.exception.ResourceNotFoundException;
import com.portfolio.ticketing.event.repository.EventRepository;
import com.portfolio.ticketing.venue.domain.Venue;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;

@ExtendWith(MockitoExtension.class)
class EventAuthorizationServiceTest {

  private static final UUID EVENT_ID = UUID.randomUUID();
  private static final UUID OWNER_ID = UUID.randomUUID();

  @Mock private EventRepository eventRepository;
  @InjectMocks private EventAuthorizationService authorizationService;

  @Test
  void should_allowOwnerOrganizer() {
    when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(eventOwnedBy(OWNER_ID)));

    assertThat(authorizationService.canManage(EVENT_ID, authentication(OWNER_ID, "ROLE_ORGANIZER")))
        .isTrue();
  }

  @Test
  void should_allowAdministratorWithoutLookingUpEvent() {
    assertThat(
            authorizationService.canManage(
                EVENT_ID, authentication(UUID.randomUUID(), "ROLE_ADMIN")))
        .isTrue();
  }

  @Test
  void should_hideEventFromDifferentOrganizerAsNotFound() {
    when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(eventOwnedBy(OWNER_ID)));

    assertThatThrownBy(
            () ->
                authorizationService.canManage(
                    EVENT_ID, authentication(UUID.randomUUID(), "ROLE_ORGANIZER")))
        .isInstanceOf(ResourceNotFoundException.class);
  }

  @Test
  void should_rejectUserRole() {
    assertThat(authorizationService.canManage(EVENT_ID, authentication(OWNER_ID, "ROLE_USER")))
        .isFalse();
  }

  @Test
  void should_allowMissingEventToReachBusinessNotFound() {
    when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.empty());

    assertThat(authorizationService.canManage(EVENT_ID, authentication(OWNER_ID, "ROLE_ORGANIZER")))
        .isTrue();
  }

  private static Event eventOwnedBy(UUID ownerId) {
    return new Event(
        ownerId,
        new Venue("Arena", "Lima", "Main St", 100),
        "Show",
        "Live",
        "MUSIC",
        Instant.parse("2099-01-01T00:00:00Z"),
        Instant.parse("2099-01-01T02:00:00Z"));
  }

  private static TestingAuthenticationToken authentication(UUID subject, String role) {
    return new TestingAuthenticationToken(subject.toString(), "n/a", role);
  }
}
