package com.portfolio.ticketing.order.repository;

import com.portfolio.ticketing.order.domain.Ticket;
import com.portfolio.ticketing.order.domain.TicketStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketRepository extends JpaRepository<Ticket, UUID> {

  Optional<Ticket> findByCode(String code);

  List<Ticket> findByOrderItem_Order_Id(UUID orderId);

  List<Ticket> findByOrderItem_Order_UserIdAndStatus(UUID userId, TicketStatus status);

  boolean existsByOrderItem_Order_Id(UUID orderId);
}
