package com.portfolio.ticketing.payment.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portfolio.ticketing.auth.config.SecurityConfig;
import com.portfolio.ticketing.auth.config.SecurityErrorHandler;
import com.portfolio.ticketing.common.exception.AuthenticationFailedException;
import com.portfolio.ticketing.payment.service.PaymentWebhookService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
    value = PaymentWebhookController.class,
    properties = "ticketing.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")
@Import({SecurityConfig.class, SecurityErrorHandler.class})
class PaymentWebhookControllerTest {

  @Autowired private MockMvc mockMvc;
  @MockitoBean private PaymentWebhookService paymentWebhookService;

  @Test
  void should_return204_forSignedWebhookWithoutAuthentication() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/payments/webhook")
                .header("X-Signature", "signature")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isNoContent());
  }

  @Test
  void should_return401_when_signatureIsInvalid() throws Exception {
    doThrow(new AuthenticationFailedException("Invalid webhook signature"))
        .when(paymentWebhookService)
        .handle(any(), any());

    mockMvc
        .perform(
            post("/api/v1/payments/webhook")
                .header("X-Signature", "invalid")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void should_return400_when_signatureHeaderIsMissing() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/payments/webhook").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(paymentWebhookService);
  }
}
