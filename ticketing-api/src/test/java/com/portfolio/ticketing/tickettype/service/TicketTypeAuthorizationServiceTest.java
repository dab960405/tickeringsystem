package com.portfolio.ticketing.tickettype.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.portfolio.ticketing.event.domain.Event;
import com.portfolio.ticketing.event.service.EventAuthorizationService;
import com.portfolio.ticketing.tickettype.domain.TicketType;
import com.portfolio.ticketing.tickettype.repository.TicketTypeRepository;
import com.portfolio.ticketing.venue.domain.Venue;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class TicketTypeAuthorizationServiceTest {

  private static final UUID TICKET_TYPE_ID = UUID.randomUUID();
  private static final UUID EVENT_ID = UUID.randomUUID();

  @Mock private TicketTypeRepository ticketTypeRepository;
  @Mock private EventAuthorizationService eventAuthorizationService;
  @InjectMocks private TicketTypeAuthorizationService authorizationService;

  @Test
  void should_delegateEventOwnershipCheck() {
    TicketType ticketType = ticketType();
    Authentication authentication = new TestingAuthenticationToken("user", "n/a", "ROLE_ORGANIZER");
    when(ticketTypeRepository.findById(TICKET_TYPE_ID)).thenReturn(Optional.of(ticketType));
    when(eventAuthorizationService.canManage(EVENT_ID, authentication)).thenReturn(true);

    assertThat(authorizationService.canManage(TICKET_TYPE_ID, authentication)).isTrue();
  }

  @Test
  void should_allowMissingTicketTypeToReachBusinessNotFound() {
    when(ticketTypeRepository.findById(TICKET_TYPE_ID)).thenReturn(Optional.empty());

    assertThat(authorizationService.canManage(TICKET_TYPE_ID, null)).isTrue();
  }

  private static TicketType ticketType() {
    Event event =
        new Event(
            UUID.randomUUID(),
            new Venue("Arena", "Lima", "Main St", 100),
            "Show",
            "Live",
            "MUSIC",
            Instant.parse("2099-01-01T00:00:00Z"),
            Instant.parse("2099-01-01T02:00:00Z"));
    ReflectionTestUtils.setField(event, "id", EVENT_ID);
    return new TicketType(
        event,
        "General",
        new BigDecimal("10.00"),
        10,
        Instant.parse("2098-01-01T00:00:00Z"),
        Instant.parse("2098-12-01T00:00:00Z"),
        2);
  }
}
