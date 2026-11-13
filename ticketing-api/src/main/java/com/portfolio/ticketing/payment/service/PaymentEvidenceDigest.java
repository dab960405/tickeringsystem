package com.portfolio.ticketing.payment.service;

import com.portfolio.ticketing.payment.api.PaymentWebhookRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.stream.Collectors;
import java.util.stream.Stream;

final class PaymentEvidenceDigest {

  private PaymentEvidenceDigest() {}

  static String semantic(PaymentWebhookRequest request) {
    String canonical =
        Stream.of(
                request.orderId().toString(),
                request.operationKind().name(),
                request.providerReference(),
                request.amount().stripTrailingZeros().toPlainString(),
                request.currency())
            .map(PaymentEvidenceDigest::lengthPrefixed)
            .collect(Collectors.joining("|"));
    return sha256(canonical);
  }

  static String raw(String payload) {
    return sha256(payload);
  }

  private static String lengthPrefixed(String value) {
    return value == null ? "null" : value.length() + ":" + value;
  }

  private static String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is not available", exception);
    }
  }
}
