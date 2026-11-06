package com.portfolio.ticketing.payment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.ticketing.common.exception.AuthenticationFailedException;
import com.portfolio.ticketing.common.exception.BusinessRuleViolationException;
import com.portfolio.ticketing.order.service.OrderPaymentService;
import com.portfolio.ticketing.payment.api.PaymentWebhookRequest;
import com.portfolio.ticketing.payment.config.PaymentProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

@Service
public class PaymentWebhookService {

  private final PaymentProperties properties;
  private final ObjectMapper objectMapper;
  private final OrderPaymentService orderPaymentService;

  public PaymentWebhookService(
      PaymentProperties properties,
      ObjectMapper objectMapper,
      OrderPaymentService orderPaymentService) {
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.orderPaymentService = orderPaymentService;
  }

  public void handle(String signature, String payload) {
    if (!hasValidSignature(signature, payload)) {
      throw new AuthenticationFailedException("Invalid webhook signature");
    }
    try {
      orderPaymentService.process(objectMapper.readValue(payload, PaymentWebhookRequest.class));
    } catch (JsonProcessingException exception) {
      throw new BusinessRuleViolationException("Invalid webhook payload");
    }
  }

  private boolean hasValidSignature(String signature, String payload) {
    if (signature == null || signature.isBlank() || payload == null) {
      return false;
    }
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(
          new SecretKeySpec(
              properties.getWebhookSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      String expected =
          HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
      return MessageDigest.isEqual(
          expected.getBytes(StandardCharsets.US_ASCII),
          signature.getBytes(StandardCharsets.US_ASCII));
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("HMAC-SHA256 is not available", exception);
    }
  }
}
