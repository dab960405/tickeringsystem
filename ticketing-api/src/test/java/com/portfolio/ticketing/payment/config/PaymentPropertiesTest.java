package com.portfolio.ticketing.payment.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class PaymentPropertiesTest {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(TestConfig.class);

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {
        " ",
        "short-secret",
        "replace-with-output-of-openssl-rand-base64-32",
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
      })
  void should_failStartup_when_secretIsMissingWeakOrPlaceholder(String secret) {
    var configured =
        secret == null
            ? runner
            : runner.withPropertyValues("ticketing.payment.webhook-secret=" + secret);
    configured.run(context ->
        assertThat(context).getFailure().rootCause().isInstanceOf(BindValidationException.class));
  }

  @Test
  void should_start_when_secretHasMinimumLengthAndIsNotPlaceholder() {
    runner
        .withPropertyValues(
            "ticketing.payment.webhook-secret=8a31ca7d5274d90e11afb04e257ba0d0fe290de5c765fbde904a33207176caaf")
        .run(context -> assertThat(context).hasNotFailed().hasSingleBean(PaymentProperties.class));
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(PaymentProperties.class)
  static class TestConfig {}
}
