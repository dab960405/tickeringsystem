package com.portfolio.ticketing.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.ticketing.order.api.TicketResponse;
import com.portfolio.ticketing.order.domain.Order;
import com.portfolio.ticketing.order.domain.Ticket;
import com.portfolio.ticketing.order.domain.TicketStatus;
import com.portfolio.ticketing.order.repository.TicketRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class TicketServiceTest {

  @Mock private TicketRepository ticketRepository;

  @Test
  void should_issueOneTicketPerReservedUnit() {
    TicketService ticketService = new TicketService(ticketRepository);
    Order order = order();
    when(ticketRepository.existsByOrderItem_Order_Id(order.getId())).thenReturn(false);

    ticketService.issueForOrder(order);

    ArgumentCaptor<List<Ticket>> captor = ArgumentCaptor.forClass(List.class);
    verify(ticketRepository).saveAll(captor.capture());
    assertThat(captor.getValue()).hasSize(2);
    assertThat(captor.getValue().stream().map(Ticket::getCode).distinct().count()).isEqualTo(2);
    assertThat(captor.getValue())
        .allMatch(ticket -> ticket.getOrderItem().equals(order.getItems().getFirst()));
  }

  @Test
  void should_notIssueTicketsTwice_forSameOrder() {
    TicketService ticketService = new TicketService(ticketRepository);
    Order order = order();
    when(ticketRepository.existsByOrderItem_Order_Id(order.getId())).thenReturn(true);

    ticketService.issueForOrder(order);

    verify(ticketRepository, never()).saveAll(any());
  }

  @Test
  void should_returnOnlyValidTicketsOwnedByUser() {
    TicketService ticketService = new TicketService(ticketRepository);
    Order order = order();
    Ticket ticket = new Ticket(order.getItems().getFirst(), "ticket-code");
    when(ticketRepository.findByOrderItem_Order_UserIdAndStatus(
            order.getUserId(), TicketStatus.VALID))
        .thenReturn(List.of(ticket));

    List<TicketResponse> responses = ticketService.listValidForUser(order.getUserId());

    assertThat(responses)
        .singleElement()
        .satisfies(response -> {
          assertThat(response.code()).isEqualTo("ticket-code");
          assertThat(response.orderId()).isEqualTo(order.getId());
          assertThat(response.ticketTypeId())
              .isEqualTo(order.getItems().getFirst().getTicketTypeId());
          assertThat(response.status()).isEqualTo(TicketStatus.VALID);
        });
  }

  private static Order order() {
    Order order = new Order(UUID.randomUUID(), UUID.randomUUID(), "key");
    order.addItem(UUID.randomUUID(), 2, new BigDecimal("40.00"));
    ReflectionTestUtils.setField(order, "id", UUID.randomUUID());
    return order;
  }
}
