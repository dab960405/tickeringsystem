package com.portfolio.ticketing.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portfolio.ticketing.common.exception.BusinessRuleViolationException;
import com.portfolio.ticketing.common.exception.InvalidStateTransitionException;
import org.junit.jupiter.api.Test;

class TicketTest {

  @Test
  void should_createValidTicketBoundToUnitAndGeneration() {
    Order order = CommercialFixtures.paidOrder(1);
    CommercialUnit unit = CommercialFixtures.unit(order.getItems().getFirst(), 1);
    CommercialGeneration generation = CommercialFixtures.activeGeneration(order);

    Ticket ticket = new Ticket(unit, generation, "ticket-code-123");

    assertThat(ticket.getCode()).isEqualTo("ticket-code-123");
    assertThat(ticket.getStatus()).isEqualTo(TicketStatus.VALID);
    assertThat(ticket.getOrderItem()).isSameAs(unit.getOrderItem());
    assertThat(ticket.getOrderId()).isEqualTo(order.getId());
    assertThat(ticket.getCommercialUnitId()).isEqualTo(unit.getId());
    assertThat(ticket.getGenerationId()).isEqualTo(generation.getId());
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
  void should_rejectCancellingUsedTicket() {
    Ticket ticket = newTicket();
    ticket.markUsed();

    assertThatThrownBy(ticket::cancel).isInstanceOf(InvalidStateTransitionException.class);
  }

  @Test
  void should_rejectBlankCode() {
    Order order = CommercialFixtures.paidOrder(1);
    CommercialUnit unit = CommercialFixtures.unit(order.getItems().getFirst(), 1);
    CommercialGeneration generation = CommercialFixtures.activeGeneration(order);

    assertThatThrownBy(() -> new Ticket(unit, generation, " "))
        .isInstanceOf(BusinessRuleViolationException.class);
  }

  @Test
  void should_rejectIssuance_when_generationIsNotActive() {
    Order order = CommercialFixtures.paidOrder(1);
    CommercialUnit unit = CommercialFixtures.unit(order.getItems().getFirst(), 1);
    CommercialGeneration generation = CommercialFixtures.activeGeneration(order);
    generation.supersede();

    assertThatThrownBy(() -> new Ticket(unit, generation, "code"))
        .isInstanceOf(InvalidStateTransitionException.class);
  }

  @Test
  void should_rejectIssuance_when_generationBelongsToAnotherOrder() {
    CommercialUnit unit =
        CommercialFixtures.unit(CommercialFixtures.paidOrder(1).getItems().getFirst(), 1);
    CommercialGeneration foreign =
        CommercialFixtures.activeGeneration(CommercialFixtures.paidOrder(1));

    assertThatThrownBy(() -> new Ticket(unit, foreign, "code"))
        .isInstanceOf(BusinessRuleViolationException.class);
  }

  private static Ticket newTicket() {
    Order order = CommercialFixtures.paidOrder(1);
    return new Ticket(
        CommercialFixtures.unit(order.getItems().getFirst(), 1),
        CommercialFixtures.activeGeneration(order),
        "ticket-code-123");
  }
}
