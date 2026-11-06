package com.portfolio.ticketing.order.service;

import com.portfolio.ticketing.order.domain.Order;
import com.portfolio.ticketing.order.domain.OrderItem;
import com.portfolio.ticketing.order.domain.Ticket;
import com.portfolio.ticketing.order.repository.TicketRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class TicketService {

  private final TicketRepository ticketRepository;

  public TicketService(TicketRepository ticketRepository) {
    this.ticketRepository = ticketRepository;
  }

  public void issueForOrder(Order order) {
    if (ticketRepository.existsByOrderItem_Order_Id(order.getId())) {
      return;
    }
    List<Ticket> tickets = new ArrayList<>();
    for (OrderItem item : order.getItems()) {
      for (int index = 0; index < item.getQuantity(); index++) {
        tickets.add(new Ticket(item, UUID.randomUUID().toString()));
      }
    }
    ticketRepository.saveAll(tickets);
  }
}
