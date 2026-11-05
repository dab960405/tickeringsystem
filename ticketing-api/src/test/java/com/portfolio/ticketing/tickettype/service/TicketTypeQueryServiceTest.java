package com.portfolio.ticketing.tickettype.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.portfolio.ticketing.event.domain.Event;
import com.portfolio.ticketing.tickettype.api.TicketTypeResponse;
import com.portfolio.ticketing.tickettype.domain.TicketType;
import com.portfolio.ticketing.tickettype.repository.TicketTypeRepository;
import com.portfolio.ticketing.venue.domain.Venue;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TicketTypeQueryServiceTest {

  private static final UUID EVENT_ID = UUID.randomUUID();

  @Mock private TicketTypeRepository ticketTypeRepository;
  @InjectMocks private TicketTypeQueryService ticketTypeQueryService;

  @Test
  void should_mapTicketTypes_when_listingForEvent() {
    Event event =
        new Event(
            UUID.randomUUID(),
            new Venue("Arena", "Lima", "Main St 1", 500),
            "Show",
            null,
            "MUSIC",
            Instant.parse("2030-01-10T20:00:00Z"),
            Instant.parse("2030-01-10T23:00:00Z"));
    TicketType general =
        new TicketType(
            event,
            "General",
            new BigDecimal("40.00"),
            100,
            Instant.parse("2030-01-01T00:00:00Z"),
            Instant.parse("2030-01-10T00:00:00Z"),
            4);
    when(ticketTypeRepository.findByEventIdOrderByPriceAsc(EVENT_ID)).thenReturn(List.of(general));

    List<TicketTypeResponse> result = ticketTypeQueryService.listForEvent(EVENT_ID);

    assertThat(result)
        .singleElement()
        .extracting(TicketTypeResponse::name, TicketTypeResponse::availableQuantity)
        .containsExactly("General", 100);
  }

  @Test
  void should_reportExistence_when_eventHasTicketTypes() {
    when(ticketTypeRepository.existsByEventId(EVENT_ID)).thenReturn(true);

    assertThat(ticketTypeQueryService.existsForEvent(EVENT_ID)).isTrue();
  }
}
