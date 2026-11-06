package com.portfolio.ticketing.payment.api;

import com.portfolio.ticketing.payment.service.PaymentWebhookService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payments")
@Tag(name = "Payments", description = "Simulated payment gateway callbacks")
public class PaymentWebhookController {

  private final PaymentWebhookService paymentWebhookService;

  public PaymentWebhookController(PaymentWebhookService paymentWebhookService) {
    this.paymentWebhookService = paymentWebhookService;
  }

  @PostMapping("/webhook")
  @Operation(summary = "Confirm or reject a payment using an HMAC-signed callback")
  @ApiResponse(responseCode = "204", description = "Webhook processed")
  @ApiResponse(responseCode = "400", description = "Missing signature")
  @ApiResponse(responseCode = "401", description = "Invalid HMAC signature")
  @ApiResponse(responseCode = "404", description = "Order or payment not found")
  @ApiResponse(responseCode = "409", description = "Invalid or conflicting payment state")
  @ApiResponse(responseCode = "422", description = "Invalid webhook payload")
  public ResponseEntity<Void> handle(
      @Parameter(description = "Hex-encoded HMAC-SHA256 of the raw request body")
          @RequestHeader("X-Signature")
          String signature,
      @RequestBody String payload) {
    paymentWebhookService.handle(signature, payload);
    return ResponseEntity.noContent().build();
  }
}
