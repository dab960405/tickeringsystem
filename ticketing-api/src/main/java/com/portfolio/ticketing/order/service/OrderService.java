package com.portfolio.ticketing.order.service;

import com.portfolio.ticketing.common.api.PageResponse;
import com.portfolio.ticketing.common.exception.BusinessRuleViolationException;
import com.portfolio.ticketing.common.exception.DuplicateResourceException;
import com.portfolio.ticketing.common.exception.ResourceNotFoundException;
import com.portfolio.ticketing.order.api.OrderRequest;
import com.portfolio.ticketing.order.api.OrderResponse;
import com.portfolio.ticketing.order.domain.Order;
import com.portfolio.ticketing.order.repository.OrderRepository;
import com.portfolio.ticketing.payment.service.PaymentService;
import com.portfolio.ticketing.reservation.domain.Reservation;
import com.portfolio.ticketing.reservation.service.ReservationService;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class OrderService {

  private final OrderRepository orderRepository;
  private final ReservationService reservationService;
  private final PaymentService paymentService;

  public OrderService(
      OrderRepository orderRepository,
      ReservationService reservationService,
      PaymentService paymentService) {
    this.orderRepository = orderRepository;
    this.reservationService = reservationService;
    this.paymentService = paymentService;
  }

  @PreAuthorize("hasRole('USER')")
  @Transactional
  public OrderCreationResult create(UUID userId, String idempotencyKey, OrderRequest request) {
    validateIdempotencyKey(idempotencyKey);
    Optional<Order> existing =
        orderRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey);
    if (existing.isPresent()) {
      Order order = existing.get();
      if (!order.getReservationId().equals(request.reservationId())) {
        throw new DuplicateResourceException(
            "Idempotency key was already used with a different reservation");
      }
      return new OrderCreationResult(OrderResponse.from(order), false);
    }

    Reservation reservation =
        reservationService.lockActiveForOrder(request.reservationId(), userId);
    if (orderRepository.existsByReservationId(reservation.getId())) {
      throw new DuplicateResourceException("An order already exists for this reservation");
    }

    Order order = new Order(userId, reservation.getId(), idempotencyKey);
    order.addItem(
        reservation.getTicketType().getId(),
        reservation.getQuantity(),
        reservation.getTicketType().getPrice());
    Order saved = orderRepository.save(order);
    paymentService.createPending(saved.getId(), saved.getTotalAmount());
    return new OrderCreationResult(OrderResponse.from(saved), true);
  }

  @PreAuthorize("hasRole('USER')")
  public PageResponse<OrderResponse> list(UUID userId, Pageable pageable) {
    return PageResponse.from(
        orderRepository
            .findByUserIdOrderByCreatedAtDesc(userId, pageable)
            .map(OrderResponse::from));
  }

  @PreAuthorize("hasRole('USER')")
  public OrderResponse get(UUID id, UUID userId) {
    return OrderResponse.from(
        orderRepository
            .findByIdAndUserId(id, userId)
            .orElseThrow(() -> new ResourceNotFoundException("Order " + id + " not found")));
  }

  private static void validateIdempotencyKey(String key) {
    if (key == null || key.isBlank() || key.length() > 100) {
      throw new BusinessRuleViolationException("Idempotency key must contain 1 to 100 characters");
    }
  }
}
