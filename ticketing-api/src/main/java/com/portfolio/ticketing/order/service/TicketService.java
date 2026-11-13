package com.portfolio.ticketing.order.service;

import com.portfolio.ticketing.order.api.TicketResponse;
import com.portfolio.ticketing.order.domain.Order;
import com.portfolio.ticketing.order.domain.OrderItem;
import com.portfolio.ticketing.order.domain.Ticket;
import com.portfolio.ticketing.order.domain.TicketStatus;
import com.portfolio.ticketing.order.repository.OrderItemRepository;
import com.portfolio.ticketing.order.repository.TicketRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class TicketService {

  private final TicketRepository ticketRepository;
  private final OrderItemRepository orderItemRepository;

  public TicketService(TicketRepository ticketRepository, OrderItemRepository orderItemRepository) {
    this.ticketRepository = ticketRepository;
    this.orderItemRepository = orderItemRepository;
  }

  public void issueForOrder(Order order) {
    if (ticketRepository.existsByOrderItem_Order_Id(order.getId())) {
      return;
    }
    List<Ticket> tickets = new ArrayList<>();
    for (OrderItem item : orderItemRepository.findByOrder_Id(order.getId())) {
      for (int index = 0; index < item.getQuantity(); index++) {
        tickets.add(new Ticket(item, UUID.randomUUID().toString()));
      }
    }
    ticketRepository.saveAll(tickets);
  }

  public void cancelForOrder(UUID orderId) {
    ticketRepository.findByOrderItem_Order_Id(orderId).stream()
        .filter(ticket -> ticket.getStatus() == TicketStatus.VALID)
        .forEach(Ticket::cancel);
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
}
