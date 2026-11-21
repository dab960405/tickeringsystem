package com.portfolio.ticketing.payment.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.ticketing.common.exception.AuthenticationFailedException;
import com.portfolio.ticketing.common.exception.BusinessRuleViolationException;
import com.portfolio.ticketing.operation.service.PaymentOperationService;
import com.portfolio.ticketing.payment.api.PaymentWebhookRequest;
import com.portfolio.ticketing.payment.config.PaymentProperties;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaymentWebhookServiceTest {

  private static final String SECRET = "test-webhook-secret";
  private static final UUID ORDER_ID = UUID.randomUUID();
  private static final String PAYLOAD =
      "{\"orderId\":\""
          + ORDER_ID
          + "\",\"status\":\"SUCCEEDED\",\"providerReference\":\"ref-1\","
          + "\"providerEventId\":\""
          + UUID.randomUUID()
          + "\",\"providerOperationId\":\"operation-1\","
          + "\"operationKind\":\"PAYMENT_CAPTURE\",\"amount\":80.00,\"currency\":\"USD\"}";

  @Mock private ProviderEventInboxService inboxService;
  @Mock private PaymentOperationService paymentOperationService;

  private PaymentWebhookService paymentWebhookService;

  @BeforeEach
  void setUp() {
    PaymentProperties properties = new PaymentProperties();
    properties.setWebhookSecret(SECRET);
    paymentWebhookService =
        new PaymentWebhookService(
            properties, new ObjectMapper(), inboxService, paymentOperationService);
  }

  @Test
  void should_delegateValidSignedPayload() throws Exception {
    ProviderEventReceipt receipt = mock(ProviderEventReceipt.class);
    when(inboxService.receive(any(PaymentWebhookRequest.class), eq(PAYLOAD))).thenReturn(receipt);
    paymentWebhookService.handle(signature(PAYLOAD), PAYLOAD);

    verify(paymentOperationService).process(receipt);
  }

  @Test
  void should_rejectInvalidSignature_beforeParsingPayload() {
    assertThatThrownBy(() -> paymentWebhookService.handle("invalid", PAYLOAD))
        .isInstanceOf(AuthenticationFailedException.class);
    verifyNoInteractions(inboxService, paymentOperationService);
  }

  @Test
  void should_rejectMalformedPayload_when_signatureIsValid() throws Exception {
    String payload = "not-json";

    assertThatThrownBy(() -> paymentWebhookService.handle(signature(payload), payload))
        .isInstanceOf(BusinessRuleViolationException.class);
    verifyNoInteractions(inboxService, paymentOperationService);
  }

  private static String signature(String payload) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
  }
}
