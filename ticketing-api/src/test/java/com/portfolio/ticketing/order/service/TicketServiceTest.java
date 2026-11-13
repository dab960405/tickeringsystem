package com.portfolio.ticketing.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.ticketing.common.exception.BusinessRuleViolationException;
import com.portfolio.ticketing.common.exception.ResourceNotFoundException;
import com.portfolio.ticketing.order.api.TicketResponse;
import com.portfolio.ticketing.order.domain.CommercialFixtures;
import com.portfolio.ticketing.order.domain.CommercialGeneration;
import com.portfolio.ticketing.order.domain.CommercialGenerationStatus;
import com.portfolio.ticketing.order.domain.CommercialUnit;
import com.portfolio.ticketing.order.domain.CommercialUnitGeneration;
import com.portfolio.ticketing.order.domain.Order;
import com.portfolio.ticketing.order.domain.Ticket;
import com.portfolio.ticketing.order.domain.TicketStatus;
import com.portfolio.ticketing.order.repository.CommercialGenerationRepository;
import com.portfolio.ticketing.order.repository.CommercialUnitGenerationRepository;
import com.portfolio.ticketing.order.repository.CommercialUnitRepository;
import com.portfolio.ticketing.order.repository.OrderItemRepository;
import com.portfolio.ticketing.order.repository.OrderRepository;
import com.portfolio.ticketing.order.repository.TicketRepository;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TicketServiceTest {

  @Mock private OrderRepository orderRepository;
  @Mock private OrderItemRepository orderItemRepository;
  @Mock private CommercialGenerationRepository generationRepository;
  @Mock private CommercialUnitRepository unitRepository;
  @Mock private CommercialUnitGenerationRepository unitGenerationRepository;
  @Mock private TicketRepository ticketRepository;
  @Mock private EntityManager entityManager;

  private TicketService ticketService;

  @BeforeEach
  void setUp() {
    ticketService =
        new TicketService(
            orderRepository,
            orderItemRepository,
            generationRepository,
            unitRepository,
            unitGenerationRepository,
            ticketRepository,
            entityManager);
    lenient()
        .when(generationRepository.save(any()))
        .thenAnswer(invocation -> CommercialFixtures.withId(invocation.getArgument(0)));
    lenient()
        .when(unitRepository.saveAll(anyList()))
        .thenAnswer(invocation -> {
          List<CommercialUnit> units = invocation.getArgument(0);
          units.forEach(CommercialFixtures::withId);
          return units;
        });
  }

  @Test
  void should_issueOneUnitLinkAndTicketPerPurchasedQuantity_inGenerationOne() {
    Order order = CommercialFixtures.paidOrder(3);
    order.markPaid();
    lockSucceeds(order);
    when(generationRepository.findByOrderIdOrderByGenerationNumberAsc(order.getId()))
        .thenReturn(List.of());
    when(orderItemRepository.findByOrder_Id(order.getId())).thenReturn(order.getItems());

    TicketIssuanceResult result = ticketService.issueForOrder(order);

    assertThat(result.issued()).isTrue();
    assertThat(result.generationNumber()).isEqualTo(1);
    assertThat(result.ticketCount()).isEqualTo(3);
    List<CommercialUnit> units = captureUnits();
    assertThat(units).extracting(CommercialUnit::getUnitNumber).containsExactly(1, 2, 3);
    List<Ticket> tickets = captureTickets();
    assertThat(tickets).hasSize(3);
    assertThat(tickets).extracting(Ticket::getCode).doesNotHaveDuplicates();
    assertThat(tickets).extracting(Ticket::getCommercialUnitId).doesNotHaveDuplicates();
    assertThat(tickets).extracting(Ticket::getGenerationId).containsOnly(result.generationId());
    assertThat(captureLinks()).hasSize(3);
  }

  @Test
  void should_lockOrderBeforeReadingGenerations() {
    Order order = CommercialFixtures.paidOrder(1);
    lockSucceeds(order);
    when(generationRepository.findByOrderIdOrderByGenerationNumberAsc(order.getId()))
        .thenReturn(List.of(CommercialFixtures.activeGeneration(order)));

    ticketService.issueForOrder(order);

    InOrder ordered = inOrder(orderRepository, generationRepository);
    ordered.verify(orderRepository).lockById(order.getId());
    ordered.verify(generationRepository).findByOrderIdOrderByGenerationNumberAsc(order.getId());
  }

  @Test
  void should_reuseExistingIssuance_when_orderAlreadyHasAGeneration() {
    Order order = CommercialFixtures.paidOrder(2);
    CommercialGeneration existing = CommercialFixtures.activeGeneration(order);
    lockSucceeds(order);
    when(generationRepository.findByOrderIdOrderByGenerationNumberAsc(order.getId()))
        .thenReturn(List.of(existing));
    when(unitGenerationRepository.countByGenerationId(existing.getId())).thenReturn(2L);

    TicketIssuanceResult result = ticketService.issueForOrder(order);

    assertThat(result).isEqualTo(new TicketIssuanceResult(existing.getId(), 1, 2, false));
    verify(generationRepository, never()).save(any());
    verify(unitRepository, never()).saveAll(anyList());
    verify(ticketRepository, never()).saveAll(anyList());
  }

  @Test
  void should_failIssuance_when_orderDoesNotExist() {
    Order order = CommercialFixtures.paidOrder(1);
    when(orderRepository.lockById(order.getId())).thenReturn(Optional.empty());

    assertThatThrownBy(() -> ticketService.issueForOrder(order))
        .isInstanceOf(ResourceNotFoundException.class);
  }

  @Test
  void should_rejectInitialIssuance_when_orderIsNotPaid() {
    Order order = CommercialFixtures.paidOrder(1);
    lockSucceeds(order);
    when(generationRepository.findByOrderIdOrderByGenerationNumberAsc(order.getId()))
        .thenReturn(List.of());

    assertThatThrownBy(() -> ticketService.issueForOrder(order))
        .isInstanceOf(BusinessRuleViolationException.class);
    verify(generationRepository, never()).save(any());
    verify(unitRepository, never()).saveAll(anyList());
  }

  @Test
  void should_reissueSameUnitsInNextGeneration_andCancelPreviousTickets() {
    Order order = fulfilledOrder(2);
    CommercialGeneration current = CommercialFixtures.activeGeneration(order);
    List<CommercialUnit> units =
        List.of(
            CommercialFixtures.unit(order.getItems().getFirst(), 1),
            CommercialFixtures.unit(order.getItems().getFirst(), 2));
    List<Ticket> previous =
        units.stream()
            .map(unit -> new Ticket(unit, current, UUID.randomUUID().toString()))
            .toList();
    lockSucceeds(order);
    when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
    when(generationRepository.findByOrderIdAndStatus(
            order.getId(), CommercialGenerationStatus.ACTIVE))
        .thenReturn(Optional.of(current));
    when(ticketRepository.findByGenerationIdOrderByCommercialUnitIdAsc(current.getId()))
        .thenReturn(previous);
    when(unitRepository.findForOrder(order.getId())).thenReturn(units);

    TicketIssuanceResult result = ticketService.reissueForOrder(order.getId());

    assertThat(result.generationNumber()).isEqualTo(2);
    assertThat(result.ticketCount()).isEqualTo(2);
    assertThat(current.getStatus()).isEqualTo(CommercialGenerationStatus.SUPERSEDED);
    assertThat(previous).extracting(Ticket::getStatus).containsOnly(TicketStatus.CANCELLED);
    List<Ticket> reissued = captureTickets();
    assertThat(reissued)
        .extracting(Ticket::getCommercialUnitId)
        .containsExactlyElementsOf(units.stream().map(CommercialUnit::getId).toList());
    assertThat(reissued).extracting(Ticket::getGenerationId).containsOnly(result.generationId());
    assertThat(reissued)
        .extracting(Ticket::getCode)
        .doesNotContainAnyElementsOf(previous.stream().map(Ticket::getCode).toList());
    InOrder ordered = inOrder(generationRepository);
    ordered.verify(generationRepository).saveAndFlush(current);
    ordered.verify(generationRepository).save(any(CommercialGeneration.class));
  }

  @Test
  void should_rejectReissue_when_generationContainsUsedTicket() {
    Order order = fulfilledOrder(1);
    CommercialGeneration current = CommercialFixtures.activeGeneration(order);
    Ticket used =
        new Ticket(CommercialFixtures.unit(order.getItems().getFirst(), 1), current, "used");
    used.markUsed();
    lockSucceeds(order);
    when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
    when(generationRepository.findByOrderIdAndStatus(
            order.getId(), CommercialGenerationStatus.ACTIVE))
        .thenReturn(Optional.of(current));
    when(ticketRepository.findByGenerationIdOrderByCommercialUnitIdAsc(current.getId()))
        .thenReturn(List.of(used));

    assertThatThrownBy(() -> ticketService.reissueForOrder(order.getId()))
        .isInstanceOf(BusinessRuleViolationException.class);
    assertThat(current.isActive()).isTrue();
    verify(ticketRepository, never()).saveAll(anyList());
  }

  @Test
  void should_rejectReissue_when_orderIsNotFulfilled() {
    Order order = CommercialFixtures.paidOrder(1);
    order.markPaid();
    order.markUnfulfillable();
    lockSucceeds(order);
    when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

    assertThatThrownBy(() -> ticketService.reissueForOrder(order.getId()))
        .isInstanceOf(BusinessRuleViolationException.class);
    verify(generationRepository, never()).save(any());
  }

  @Test
  void should_rejectReissue_when_refundWasRequested() {
    Order order = fulfilledOrder(1);
    order.requestRefund();
    lockSucceeds(order);
    when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

    assertThatThrownBy(() -> ticketService.reissueForOrder(order.getId()))
        .isInstanceOf(BusinessRuleViolationException.class);
  }

  @Test
  void should_rejectReissue_when_noActiveGenerationExists() {
    Order order = fulfilledOrder(1);
    lockSucceeds(order);
    when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
    when(generationRepository.findByOrderIdAndStatus(
            order.getId(), CommercialGenerationStatus.ACTIVE))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> ticketService.reissueForOrder(order.getId()))
        .isInstanceOf(BusinessRuleViolationException.class);
  }

  @Test
  void should_cancelActiveGenerationAndValidTickets_when_refundIsConfirmed() {
    Order order = CommercialFixtures.paidOrder(1);
    CommercialGeneration current = CommercialFixtures.activeGeneration(order);
    Ticket ticket =
        new Ticket(CommercialFixtures.unit(order.getItems().getFirst(), 1), current, "code");
    lockSucceeds(order);
    when(generationRepository.findByOrderIdAndStatus(
            order.getId(), CommercialGenerationStatus.ACTIVE))
        .thenReturn(Optional.of(current));
    when(ticketRepository.findByGenerationIdOrderByCommercialUnitIdAsc(current.getId()))
        .thenReturn(List.of(ticket));

    ticketService.cancelForOrder(order.getId());

    assertThat(ticket.getStatus()).isEqualTo(TicketStatus.CANCELLED);
    assertThat(current.getStatus()).isEqualTo(CommercialGenerationStatus.CANCELLED);
  }

  @Test
  void should_doNothingOnCancel_when_orderHasNoActiveGeneration() {
    Order order = CommercialFixtures.paidOrder(1);
    lockSucceeds(order);
    when(generationRepository.findByOrderIdAndStatus(
            order.getId(), CommercialGenerationStatus.ACTIVE))
        .thenReturn(Optional.empty());

    ticketService.cancelForOrder(order.getId());

    verify(ticketRepository, never()).findByGenerationIdOrderByCommercialUnitIdAsc(any());
  }

  @Test
  void should_returnOnlyValidTicketsOwnedByUser() {
    Order order = CommercialFixtures.paidOrder(1);
    CommercialUnit unit = CommercialFixtures.unit(order.getItems().getFirst(), 1);
    CommercialGeneration generation = CommercialFixtures.activeGeneration(order);
    Ticket ticket = new Ticket(unit, generation, "ticket-code");
    when(ticketRepository.findByOrderItem_Order_UserIdAndStatus(
            order.getUserId(), TicketStatus.VALID))
        .thenReturn(List.of(ticket));

    List<TicketResponse> responses = ticketService.listValidForUser(order.getUserId());

    assertThat(responses)
        .containsExactly(
            new TicketResponse(
                "ticket-code",
                order.getId(),
                order.getItems().getFirst().getTicketTypeId(),
                unit.getId(),
                generation.getId(),
                TicketStatus.VALID));
  }

  private static Order fulfilledOrder(int quantity) {
    Order order = CommercialFixtures.paidOrder(quantity);
    order.markPaid();
    order.markFulfilled();
    return order;
  }

  private void lockSucceeds(Order order) {
    when(orderRepository.lockById(order.getId())).thenReturn(Optional.of(order.getId()));
    when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
  }

  @SuppressWarnings("unchecked")
  private List<CommercialUnit> captureUnits() {
    ArgumentCaptor<List<CommercialUnit>> captor = ArgumentCaptor.forClass(List.class);
    verify(unitRepository).saveAll(captor.capture());
    return captor.getValue();
  }

  @SuppressWarnings("unchecked")
  private List<Ticket> captureTickets() {
    ArgumentCaptor<List<Ticket>> captor = ArgumentCaptor.forClass(List.class);
    verify(ticketRepository).saveAll(captor.capture());
    return captor.getValue();
  }

  @SuppressWarnings("unchecked")
  private List<CommercialUnitGeneration> captureLinks() {
    ArgumentCaptor<List<CommercialUnitGeneration>> captor = ArgumentCaptor.forClass(List.class);
    verify(unitGenerationRepository).saveAll(captor.capture());
    return captor.getValue();
  }
}
