package com.portfolio.ticketing.tickettype.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.ticketing.common.exception.BusinessRuleViolationException;
import com.portfolio.ticketing.common.exception.InsufficientStockException;
import com.portfolio.ticketing.common.exception.ResourceNotFoundException;
import com.portfolio.ticketing.tickettype.domain.TicketType;
import com.portfolio.ticketing.tickettype.repository.TicketTypeRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TicketTypeInventoryServiceTest {

  private static final UUID ID = UUID.randomUUID();

  @Mock private TicketTypeRepository ticketTypeRepository;
  @InjectMocks private TicketTypeInventoryService inventoryService;

  @Test
  void should_returnTicketType_when_itExists() {
    TicketType ticketType = org.mockito.Mockito.mock(TicketType.class);
    when(ticketTypeRepository.findById(ID)).thenReturn(Optional.of(ticketType));

    assertThat(inventoryService.findRequired(ID)).isSameAs(ticketType);
  }

  @Test
  void should_throwNotFound_when_ticketTypeDoesNotExist() {
    when(ticketTypeRepository.findById(ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> inventoryService.findRequired(ID))
        .isInstanceOf(ResourceNotFoundException.class);
  }

  @Test
  void should_lockTicketType_when_itExists() {
    TicketType ticketType = org.mockito.Mockito.mock(TicketType.class);
    when(ticketTypeRepository.findByIdForUpdate(ID)).thenReturn(Optional.of(ticketType));

    assertThat(inventoryService.lockRequired(ID)).isSameAs(ticketType);
  }

  @Test
  void should_lockTicketTypesOnceInAscendingOrder() {
    UUID high = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
    UUID low = UUID.fromString("00000000-0000-0000-0000-000000000001");
    when(ticketTypeRepository.findAllByIdForUpdate(List.of(low, high)))
        .thenReturn(
            List.of(
                org.mockito.Mockito.mock(TicketType.class),
                org.mockito.Mockito.mock(TicketType.class)));

    inventoryService.lockAll(List.of(high, low, high));

    verify(ticketTypeRepository).findAllByIdForUpdate(List.of(low, high));
  }

  @Test
  void should_rejectLockBatch_when_aTicketTypeDisappears() {
    when(ticketTypeRepository.findAllByIdForUpdate(List.of(ID))).thenReturn(List.of());

    assertThatThrownBy(() -> inventoryService.lockAll(List.of(ID)))
        .isInstanceOf(ResourceNotFoundException.class);
  }

  @Test
  void should_reserveStock_when_atomicUpdateAffectsOneRow() {
    when(ticketTypeRepository.reserveStock(ID, 2)).thenReturn(1);

    inventoryService.reserve(ID, 2);

    verify(ticketTypeRepository).reserveStock(ID, 2);
  }

  @Test
  void should_throwInsufficientStock_when_atomicUpdateAffectsNoRows() {
    when(ticketTypeRepository.reserveStock(ID, 2)).thenReturn(0);

    assertThatThrownBy(() -> inventoryService.reserve(ID, 2))
        .isInstanceOf(InsufficientStockException.class);
  }

  @Test
  void should_returnTrue_when_tryReserveFindsStock() {
    when(ticketTypeRepository.reserveStock(ID, 2)).thenReturn(1);

    assertThat(inventoryService.tryReserve(ID, 2)).isTrue();
  }

  @Test
  void should_returnFalse_when_tryReserveFindsNoStock() {
    when(ticketTypeRepository.reserveStock(ID, 2)).thenReturn(0);

    assertThat(inventoryService.tryReserve(ID, 2)).isFalse();
  }

  @Test
  void should_restoreStock_when_reservationIsReleased() {
    when(ticketTypeRepository.restoreStock(ID, 2)).thenReturn(1);

    inventoryService.release(ID, 2);

    verify(ticketTypeRepository).restoreStock(ID, 2);
  }

  @Test
  void should_rejectRelease_when_stockInvariantWouldBeBroken() {
    when(ticketTypeRepository.restoreStock(ID, 2)).thenReturn(0);

    assertThatThrownBy(() -> inventoryService.release(ID, 2))
        .isInstanceOf(BusinessRuleViolationException.class);
  }

  @Test
  void should_rejectNonPositiveQuantity_when_reservingStock() {
    assertThatThrownBy(() -> inventoryService.reserve(ID, 0))
        .isInstanceOf(BusinessRuleViolationException.class);
  }
}
