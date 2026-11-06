package com.portfolio.ticketing.tickettype.service;

import com.portfolio.ticketing.common.exception.BusinessRuleViolationException;
import com.portfolio.ticketing.common.exception.InsufficientStockException;
import com.portfolio.ticketing.common.exception.ResourceNotFoundException;
import com.portfolio.ticketing.tickettype.domain.TicketType;
import com.portfolio.ticketing.tickettype.repository.TicketTypeRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TicketTypeInventoryService {

  private final TicketTypeRepository ticketTypeRepository;

  public TicketTypeInventoryService(TicketTypeRepository ticketTypeRepository) {
    this.ticketTypeRepository = ticketTypeRepository;
  }

  @Transactional(readOnly = true)
  public TicketType findRequired(UUID id) {
    return ticketTypeRepository
        .findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("Ticket type " + id + " not found"));
  }

  @Transactional
  public void reserve(UUID id, int quantity) {
    if (!tryReserve(id, quantity)) {
      throw new InsufficientStockException("Not enough tickets available for ticket type " + id);
    }
  }

  @Transactional
  public boolean tryReserve(UUID id, int quantity) {
    requirePositive(quantity);
    return ticketTypeRepository.reserveStock(id, quantity) == 1;
  }

  @Transactional
  public void release(UUID id, int quantity) {
    requirePositive(quantity);
    if (ticketTypeRepository.restoreStock(id, quantity) == 0) {
      throw new BusinessRuleViolationException("Stock could not be restored for ticket type " + id);
    }
  }

  private static void requirePositive(int quantity) {
    if (quantity <= 0) {
      throw new BusinessRuleViolationException("Stock quantity must be positive");
    }
  }
}
