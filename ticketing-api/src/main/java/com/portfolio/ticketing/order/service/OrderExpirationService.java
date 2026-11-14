package com.portfolio.ticketing.order.service;

import com.portfolio.ticketing.order.repository.OrderExpirationCandidate;
import com.portfolio.ticketing.order.repository.OrderRepository;
import java.util.List;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class OrderExpirationService {

  private static final int BATCH_SIZE = 100;

  private final OrderRepository orderRepository;
  private final OrderExpirationProcessor processor;

  public OrderExpirationService(
      OrderRepository orderRepository, OrderExpirationProcessor processor) {
    this.orderRepository = orderRepository;
    this.processor = processor;
  }

  /**
   * Expires pending orders 15 minutes after their reservation deadline. Each candidate is
   * processed in a separate transaction so locks from one order cannot overlap the next one.
   */
  @Scheduled(
      fixedDelayString = "${ticketing.order.expiration-fixed-delay-ms:30000}",
      initialDelayString = "${ticketing.order.expiration-fixed-delay-ms:30000}")
  public void expireOverdue() {
    List<OrderExpirationCandidate> candidates =
        orderRepository.findExpiredPendingOrderCandidates(BATCH_SIZE);
    for (OrderExpirationCandidate candidate : candidates) {
      processor.expire(candidate);
    }
  }
}
