package com.portfolio.ticketing.operation.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Duration;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class OperationPropertiesTest {

  private static Validator validator;

  @BeforeAll
  static void createValidator() {
    validator = Validation.buildDefaultValidatorFactory().getValidator();
  }

  @Test
  void should_acceptDocumentedDefaults_when_nothingIsConfigured() {
    OperationProperties properties = new OperationProperties();

    assertThat(validator.validate(properties)).isEmpty();
    assertThat(properties)
        .extracting(
            OperationProperties::getTxApplyTimeout,
            OperationProperties::getLeaseDuration,
            OperationProperties::getBatchSize,
            OperationProperties::getMaxAttempts,
            OperationProperties::getRetryBackoff)
        .containsExactly(
            Duration.ofSeconds(5), Duration.ofSeconds(30), 50, 3, Duration.ofSeconds(5));
  }

  static Stream<Arguments> invalidConfigurations() {
    return Stream.of(
        Arguments.of("batch above 100", (Consumer<OperationProperties>) p -> p.setBatchSize(101)),
        Arguments.of("batch below 1", (Consumer<OperationProperties>) p -> p.setBatchSize(0)),
        Arguments.of(
            "attempts above 10", (Consumer<OperationProperties>) p -> p.setMaxAttempts(11)),
        Arguments.of(
            "apply above 5s",
            (Consumer<OperationProperties>) p -> p.setTxApplyTimeout(Duration.ofSeconds(6))),
        Arguments.of(
            "fractional tx timeout",
            (Consumer<OperationProperties>) p -> p.setTxRxTimeout(Duration.ofMillis(1500))),
        Arguments.of(
            "lock not shorter than tx",
            (Consumer<OperationProperties>) p -> p.setLockTimeout(Duration.ofSeconds(3))),
        Arguments.of(
            "lease not above apply",
            (Consumer<OperationProperties>) p -> p.setLeaseDuration(Duration.ofSeconds(5))),
        Arguments.of(
            "lease above 10m",
            (Consumer<OperationProperties>) p -> p.setLeaseDuration(Duration.ofMinutes(11))),
        Arguments.of(
            "negative backoff",
            (Consumer<OperationProperties>) p -> p.setRetryBackoff(Duration.ofSeconds(-1))),
        Arguments.of(
            "recovery too frequent",
            (Consumer<OperationProperties>) p -> p.setRecoveryInterval(Duration.ofMillis(10))),
        Arguments.of(
            "blank provider code",
            (Consumer<OperationProperties>) p -> p.getProvider().setCode("")),
        Arguments.of(
            "lowercase provider code",
            (Consumer<OperationProperties>) p -> p.getProvider().setCode("simulated")),
        Arguments.of(
            "uppercase namespace",
            (Consumer<OperationProperties>) p -> p.getProvider().setIdentityNamespace("Payments")));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidConfigurations")
  void should_rejectConfiguration_when_limitIsViolated(
      String description, Consumer<OperationProperties> mutation) {
    OperationProperties properties = new OperationProperties();
    mutation.accept(properties);

    Set<ConstraintViolation<OperationProperties>> violations = validator.validate(properties);

    assertThat(violations).isNotEmpty();
  }

  @Test
  void should_deriveStatementTimeout_when_transactionTimeoutIsKnown() {
    OperationProperties properties = new OperationProperties();

    assertThat(properties.statementTimeout(Duration.ofSeconds(5))).isEqualTo(Duration.ofSeconds(4));
    assertThat(properties.statementTimeout(Duration.ofSeconds(1))).isEqualTo(Duration.ofSeconds(1));
  }

  @Test
  void should_failStartup_when_boundPropertiesAreInvalid() {
    new ApplicationContextRunner()
        .withUserConfiguration(TestConfig.class)
        .withPropertyValues("ticketing.operation.tx-apply-timeout=10s")
        .run(context ->
            assertThat(context)
                .getFailure()
                .rootCause()
                .isInstanceOf(BindValidationException.class)
                .hasMessageContaining("tx-apply-timeout must not exceed 5s"));
  }

  @Configuration
  @EnableConfigurationProperties(OperationProperties.class)
  static class TestConfig {}
}
