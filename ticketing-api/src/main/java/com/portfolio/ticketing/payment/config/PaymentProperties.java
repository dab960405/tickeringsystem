package com.portfolio.ticketing.payment.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "ticketing.payment")
public class PaymentProperties {

  @NotBlank(message = "PAYMENT_WEBHOOK_SECRET is required")
  private String webhookSecret;

  @AssertTrue(
      message =
          "PAYMENT_WEBHOOK_SECRET must contain at least 32 characters and must not be an example or repeated character")
  public boolean isWebhookSecretAcceptable() {
    if (webhookSecret == null || webhookSecret.isBlank()) {
      return true; // @NotBlank reports missing configuration.
    }
    String normalized = webhookSecret.toLowerCase(Locale.ROOT);
    return webhookSecret.length() >= 32
        && !normalized.startsWith("replace-")
        && !normalized.startsWith("changeme")
        && !normalized.startsWith("change-me")
        && webhookSecret.codePoints().distinct().count() > 1;
  }

  public String getWebhookSecret() {
    return webhookSecret;
  }

  public void setWebhookSecret(String webhookSecret) {
    this.webhookSecret = webhookSecret;
  }
}
