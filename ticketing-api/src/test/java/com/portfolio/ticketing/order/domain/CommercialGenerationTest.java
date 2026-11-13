package com.portfolio.ticketing.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portfolio.ticketing.common.exception.BusinessRuleViolationException;
import com.portfolio.ticketing.common.exception.InvalidStateTransitionException;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class CommercialGenerationTest {

  @Test
  void should_startAsActiveGenerationOne() {
    UUID orderId = UUID.randomUUID();

    CommercialGeneration generation = CommercialGeneration.initial(orderId);

    assertThat(generation.getOrderId()).isEqualTo(orderId);
    assertThat(generation.getGenerationNumber()).isEqualTo(1);
    assertThat(generation.getStatus()).isEqualTo(CommercialGenerationStatus.ACTIVE);
    assertThat(generation.isActive()).isTrue();
  }

  @Test
  void should_createNextNumberForSameOrder_when_superseded() {
    CommercialGeneration generation = CommercialGeneration.initial(UUID.randomUUID());
    generation.supersede();

    CommercialGeneration successor = generation.successor();

    assertThat(successor.getOrderId()).isEqualTo(generation.getOrderId());
    assertThat(successor.getGenerationNumber()).isEqualTo(2);
    assertThat(successor.isActive()).isTrue();
  }

  @Test
  void should_rejectSuccessor_when_generationIsStillActive() {
    CommercialGeneration generation = CommercialGeneration.initial(UUID.randomUUID());

    assertThatThrownBy(generation::successor).isInstanceOf(InvalidStateTransitionException.class);
  }

  @Test
  void should_rejectSuccessor_when_generationWasCancelled() {
    CommercialGeneration generation = CommercialGeneration.initial(UUID.randomUUID());
    generation.cancel();

    assertThatThrownBy(generation::successor).isInstanceOf(InvalidStateTransitionException.class);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("finalTransitions")
  void should_rejectAnyTransition_fromFinalStatus(
      String description,
      Consumer<CommercialGeneration> toFinal,
      Consumer<CommercialGeneration> next) {
    CommercialGeneration generation = CommercialGeneration.initial(UUID.randomUUID());
    toFinal.accept(generation);

    assertThatThrownBy(() -> next.accept(generation))
        .isInstanceOf(InvalidStateTransitionException.class);
  }

  static Stream<Arguments> finalTransitions() {
    Consumer<CommercialGeneration> supersede = CommercialGeneration::supersede;
    Consumer<CommercialGeneration> cancel = CommercialGeneration::cancel;
    return Stream.of(
        Arguments.of("SUPERSEDED -> SUPERSEDED", supersede, supersede),
        Arguments.of("SUPERSEDED -> CANCELLED", supersede, cancel),
        Arguments.of("CANCELLED -> CANCELLED", cancel, cancel),
        Arguments.of("CANCELLED -> SUPERSEDED", cancel, supersede));
  }

  @Test
  void should_rejectNullOrder() {
    assertThatThrownBy(() -> CommercialGeneration.initial(null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void should_bindUnitToItemAndOrder() {
    Order order = CommercialFixtures.paidOrder(2);
    OrderItem item = order.getItems().getFirst();

    CommercialUnit unit = new CommercialUnit(item, 2);

    assertThat(unit.getOrderId()).isEqualTo(order.getId());
    assertThat(unit.getOrderItem()).isSameAs(item);
    assertThat(unit.getUnitNumber()).isEqualTo(2);
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(ints = {0, -1, 3})
  void should_rejectUnitNumberOutsideItemQuantity(int unitNumber) {
    OrderItem item = CommercialFixtures.paidOrder(2).getItems().getFirst();

    assertThatThrownBy(() -> new CommercialUnit(item, unitNumber))
        .isInstanceOf(BusinessRuleViolationException.class);
  }

  @Test
  void should_linkUnitAndGenerationOfSameOrder() {
    Order order = CommercialFixtures.paidOrder(1);
    CommercialUnit unit = CommercialFixtures.unit(order.getItems().getFirst(), 1);
    CommercialGeneration generation = CommercialFixtures.activeGeneration(order);

    CommercialUnitGeneration link = new CommercialUnitGeneration(unit, generation);

    assertThat(link.getOrderId()).isEqualTo(order.getId());
    assertThat(link.getCommercialUnitId()).isEqualTo(unit.getId());
    assertThat(link.getGenerationId()).isEqualTo(generation.getId());
  }

  @Test
  void should_rejectLinkAcrossOrders() {
    CommercialUnit unit =
        CommercialFixtures.unit(CommercialFixtures.paidOrder(1).getItems().getFirst(), 1);
    CommercialGeneration foreign =
        CommercialFixtures.activeGeneration(CommercialFixtures.paidOrder(1));

    assertThatThrownBy(() -> new CommercialUnitGeneration(unit, foreign))
        .isInstanceOf(BusinessRuleViolationException.class);
  }

  @Test
  void should_rejectLink_when_unitIsNotPersisted() {
    Order order = CommercialFixtures.paidOrder(1);
    CommercialUnit transientUnit = new CommercialUnit(order.getItems().getFirst(), 1);
    CommercialGeneration generation = CommercialFixtures.activeGeneration(order);

    assertThatThrownBy(() -> new CommercialUnitGeneration(transientUnit, generation))
        .isInstanceOf(NullPointerException.class);
  }
}
