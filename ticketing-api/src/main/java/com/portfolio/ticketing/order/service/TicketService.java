package com.portfolio.ticketing.order.service;

import com.portfolio.ticketing.common.exception.BusinessRuleViolationException;
import com.portfolio.ticketing.common.exception.ResourceNotFoundException;
import com.portfolio.ticketing.order.api.TicketResponse;
import com.portfolio.ticketing.order.domain.CommercialGeneration;
import com.portfolio.ticketing.order.domain.CommercialGenerationStatus;
import com.portfolio.ticketing.order.domain.CommercialUnit;
import com.portfolio.ticketing.order.domain.CommercialUnitGeneration;
import com.portfolio.ticketing.order.domain.FulfillmentStatus;
import com.portfolio.ticketing.order.domain.Order;
import com.portfolio.ticketing.order.domain.OrderItem;
import com.portfolio.ticketing.order.domain.OrderStatus;
import com.portfolio.ticketing.order.domain.RefundStatus;
import com.portfolio.ticketing.order.domain.Ticket;
import com.portfolio.ticketing.order.domain.TicketStatus;
import com.portfolio.ticketing.order.repository.CommercialGenerationRepository;
import com.portfolio.ticketing.order.repository.CommercialUnitGenerationRepository;
import com.portfolio.ticketing.order.repository.CommercialUnitRepository;
import com.portfolio.ticketing.order.repository.OrderItemRepository;
import com.portfolio.ticketing.order.repository.OrderRepository;
import com.portfolio.ticketing.order.repository.TicketRepository;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues tickets as commercial units and generations (ADR-017).
 *
 * <p>Every mutating path first acquires lock O on the order (ADR-016), so issuance, reissuance and
 * cancellation of one order are serialized; generations are only written while O is held. Unique
 * constraints remain the last defense against duplicate issuance.
 */
@Service
@Transactional
public class TicketService {

  private final OrderRepository orderRepository;
  private final OrderItemRepository orderItemRepository;
  private final CommercialGenerationRepository generationRepository;
  private final CommercialUnitRepository unitRepository;
  private final CommercialUnitGenerationRepository unitGenerationRepository;
  private final TicketRepository ticketRepository;
  private final EntityManager entityManager;

  public TicketService(
      OrderRepository orderRepository,
      OrderItemRepository orderItemRepository,
      CommercialGenerationRepository generationRepository,
      CommercialUnitRepository unitRepository,
      CommercialUnitGenerationRepository unitGenerationRepository,
      TicketRepository ticketRepository,
      EntityManager entityManager) {
    this.orderRepository = orderRepository;
    this.orderItemRepository = orderItemRepository;
    this.generationRepository = generationRepository;
    this.unitRepository = unitRepository;
    this.unitGenerationRepository = unitGenerationRepository;
    this.ticketRepository = ticketRepository;
    this.entityManager = entityManager;
  }

  /** Initial issuance: generation 1 with one unit and one ticket per purchased quantity. */
  @Transactional(propagation = Propagation.MANDATORY)
  public TicketIssuanceResult issueForOrder(Order order) {
    Order lockedOrder = lockOrder(order.getId());
    List<CommercialGeneration> generations =
        generationRepository.findByOrderIdOrderByGenerationNumberAsc(lockedOrder.getId());
    if (!generations.isEmpty()) {
      CommercialGeneration latest = generations.getLast();
      return new TicketIssuanceResult(
          latest.getId(),
          latest.getGenerationNumber(),
          (int) unitGenerationRepository.countByGenerationId(latest.getId()),
          false);
    }
    if (lockedOrder.getStatus() != OrderStatus.PAID
        || lockedOrder.getFulfillmentStatus() != FulfillmentStatus.PENDING
        || lockedOrder.getRefundStatus() != RefundStatus.NONE) {
      throw new BusinessRuleViolationException(
          "Only a paid order with pending fulfillment can issue its initial generation");
    }

    CommercialGeneration generation =
        generationRepository.save(CommercialGeneration.initial(lockedOrder.getId()));
    List<CommercialUnit> units = new ArrayList<>();
    for (OrderItem item : itemsInIssuanceOrder(lockedOrder.getId())) {
      for (int unitNumber = 1; unitNumber <= item.getQuantity(); unitNumber++) {
        units.add(new CommercialUnit(item, unitNumber));
      }
    }
    List<CommercialUnit> savedUnits = unitRepository.saveAll(units);
    issueGeneration(savedUnits, generation);
    return new TicketIssuanceResult(
        generation.getId(), generation.getGenerationNumber(), savedUnits.size(), true);
  }

  /**
   * Replaces the active generation of a paid, fulfilled order: previous tickets are cancelled, the
   * generation is superseded and the same units receive new codes in the next generation.
   */
  public TicketIssuanceResult reissueForOrder(UUID orderId) {
    Order order = lockOrder(orderId);
    if (order.getStatus() != OrderStatus.PAID
        || order.getFulfillmentStatus() != FulfillmentStatus.FULFILLED
        || order.getRefundStatus() != RefundStatus.NONE) {
      throw new BusinessRuleViolationException(
          "Only a paid, fulfilled order without refund obligations can be reissued");
    }
    CommercialGeneration current = requireActiveGeneration(orderId);
    List<Ticket> currentTickets =
        ticketRepository.findByGenerationIdOrderByCommercialUnitIdAsc(current.getId());
    if (currentTickets.stream().anyMatch(ticket -> ticket.getStatus() == TicketStatus.USED)) {
      throw new BusinessRuleViolationException("A generation with used tickets cannot be reissued");
    }

    currentTickets.stream()
        .filter(ticket -> ticket.getStatus() == TicketStatus.VALID)
        .forEach(Ticket::cancel);
    current.supersede();
    // Flush before inserting the successor: the partial unique index allows one ACTIVE row.
    generationRepository.saveAndFlush(current);

    CommercialGeneration successor = generationRepository.save(current.successor());
    List<CommercialUnit> units = unitRepository.findForOrder(orderId);
    issueGeneration(units, successor);
    return new TicketIssuanceResult(
        successor.getId(), successor.getGenerationNumber(), units.size(), true);
  }

  /** Cancels the active generation and its valid tickets once a refund is confirmed. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void cancelForOrder(UUID orderId) {
    lockOrder(orderId);
    generationRepository
        .findByOrderIdAndStatus(orderId, CommercialGenerationStatus.ACTIVE)
        .ifPresent(generation -> {
          ticketRepository.findByGenerationIdOrderByCommercialUnitIdAsc(generation.getId()).stream()
              .filter(ticket -> ticket.getStatus() == TicketStatus.VALID)
              .forEach(Ticket::cancel);
          generation.cancel();
        });
  }

  @PreAuthorize("hasRole('USER')")
  @Transactional(readOnly = true)
  public List<TicketResponse> listValidForUser(UUID userId) {
    return ticketRepository
        .findByOrderItem_Order_UserIdAndStatus(userId, TicketStatus.VALID)
        .stream()
        .map(TicketResponse::from)
        .toList();
  }

  private void issueGeneration(List<CommercialUnit> units, CommercialGeneration generation) {
    List<CommercialUnitGeneration> links = new ArrayList<>(units.size());
    List<Ticket> tickets = new ArrayList<>(units.size());
    for (CommercialUnit unit : units) {
      links.add(new CommercialUnitGeneration(unit, generation));
      tickets.add(new Ticket(unit, generation, UUID.randomUUID().toString()));
    }
    unitGenerationRepository.saveAll(links);
    ticketRepository.saveAll(tickets);
  }

  private List<OrderItem> itemsInIssuanceOrder(UUID orderId) {
    return orderItemRepository.findByOrder_Id(orderId).stream()
        .sorted(Comparator.comparing(OrderItem::getId))
        .toList();
  }

  private CommercialGeneration requireActiveGeneration(UUID orderId) {
    return generationRepository
        .findByOrderIdAndStatus(orderId, CommercialGenerationStatus.ACTIVE)
        .orElseThrow(() ->
            new BusinessRuleViolationException("Order " + orderId + " has no active tickets"));
  }

  private Order lockOrder(UUID orderId) {
    entityManager.flush();
    orderRepository
        .lockById(orderId)
        .orElseThrow(() -> new ResourceNotFoundException("Order " + orderId + " not found"));
    Order lockedOrder =
        orderRepository
            .findById(orderId)
            .orElseThrow(() -> new ResourceNotFoundException("Order " + orderId + " not found"));
    entityManager.refresh(lockedOrder);
    return lockedOrder;
  }
}
