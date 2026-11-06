package com.portfolio.ticketing.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portfolio.ticketing.common.exception.InvalidStateTransitionException;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class OrderStatusTest {

  static Stream<Arguments> validTransitions() {
    return Stream.of(
        Arguments.of(OrderStatus.PENDING_PAYMENT, OrderStatus.PAID),
        Arguments.of(OrderStatus.PENDING_PAYMENT, OrderStatus.CANCELLED),
        Arguments.of(OrderStatus.PENDING_PAYMENT, OrderStatus.EXPIRED),
        Arguments.of(OrderStatus.PAID, OrderStatus.REFUNDED));
  }

  @ParameterizedTest
  @MethodSource("validTransitions")
  void should_transition_when_targetIsAllowed(OrderStatus source, OrderStatus target) {
    assertThat(source.transitionTo(target)).isEqualTo(target);
  }

  static Stream<Arguments> invalidTransitions() {
    return Arrays.stream(OrderStatus.values())
        .flatMap(source ->
            Arrays.stream(OrderStatus.values())
                .filter(target -> !source.canTransitionTo(target))
                .map(target -> Arguments.of(source, target)));
  }

  @ParameterizedTest
  @MethodSource("invalidTransitions")
  void should_throwInvalidStateTransition_when_targetIsNotAllowed(
      OrderStatus source, OrderStatus target) {
    assertThatThrownBy(() -> source.transitionTo(target))
        .isInstanceOf(InvalidStateTransitionException.class);
  }

  @ParameterizedTest
  @MethodSource("validTransitions")
  void should_reportAllowedTransition_when_targetIsAllowed(OrderStatus source, OrderStatus target) {
    assertThat(source.canTransitionTo(target)).isTrue();
  }

  @ParameterizedTest
  @MethodSource("invalidTransitions")
  void should_reportRejectedTransition_when_targetIsNotAllowed(
      OrderStatus source, OrderStatus target) {
    assertThat(source.canTransitionTo(target)).isFalse();
  }
}
