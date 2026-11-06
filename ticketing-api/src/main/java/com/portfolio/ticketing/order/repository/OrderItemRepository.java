package com.portfolio.ticketing.order.repository;

import com.portfolio.ticketing.order.domain.OrderItem;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderItemRepository extends JpaRepository<OrderItem, UUID> {

  List<OrderItem> findByOrder_Id(UUID orderId);
}
