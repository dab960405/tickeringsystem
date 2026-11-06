package com.portfolio.ticketing.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portfolio.ticketing.common.exception.BusinessRuleViolationException;
import com.portfolio.ticketing.common.exception.InvalidStateTransitionException;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TicketTest {

  @Test
  void should_createValidTicketWithUniqueCode() {
    Ticket ticket = newTicket();

    assertThat(ticket.getCode()).isEqualTo("ticket-code-123");
    assertThat(ticket.getStatus()).isEqualTo(TicketStatus.VALID);
    assertThat(ticket.getOrderItem()).isNotNull();
  }

  @Test
  void should_markValidTicketAsUsed() {
    Ticket ticket = newTicket();

    ticket.markUsed();

    assertThat(ticket.getStatus()).isEqualTo(TicketStatus.USED);
  }

  @Test
  void should_cancelValidTicket() {
    Ticket ticket = newTicket();

    ticket.cancel();

    assertThat(ticket.getStatus()).isEqualTo(TicketStatus.CANCELLED);
  }

  @Test
  void should_rejectUsingTicketTwice() {
    Ticket ticket = newTicket();
    ticket.markUsed();

    assertThatThrownBy(ticket::markUsed).isInstanceOf(InvalidStateTransitionException.class);
  }

  @Test
  void should_rejectBlankCode() {
    OrderItem item = newOrderItem();

    assertThatThrownBy(() -> new Ticket(item, " "))
        .isInstanceOf(BusinessRuleViolationException.class);
  }

  private static Ticket newTicket() {
    return new Ticket(newOrderItem(), "ticket-code-123");
  }

  private static OrderItem newOrderItem() {
    Order order = new Order(UUID.randomUUID(), UUID.randomUUID(), "key");
    return order.addItem(UUID.randomUUID(), 1, new BigDecimal("40.00"));
  }
}
