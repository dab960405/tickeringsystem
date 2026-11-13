package com.portfolio.ticketing.operation.service;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.ticketing.operation.config.OperationProperties;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OperationRecoveryWorkerTest {

  @Mock private OperationJobService jobService;
  @Mock private PaymentOperationService paymentOperationService;

  private OperationRecoveryWorker worker;

  @BeforeEach
  void setUp() {
    OperationProperties properties = new OperationProperties();
    properties.setBatchSize(2);
    worker = new OperationRecoveryWorker(jobService, paymentOperationService, properties);
  }

  @Test
  void should_processConfiguredRecoveryBatch() {
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    when(jobService.findRecoverableJobIds(2)).thenReturn(List.of(first, second));

    worker.recover();

    verify(paymentOperationService).processJob(eq(first), anyString());
    verify(paymentOperationService).processJob(eq(second), anyString());
  }

  @Test
  void should_continueBatchAfterStaleExecution() {
    UUID stale = UUID.randomUUID();
    UUID next = UUID.randomUUID();
    when(jobService.findRecoverableJobIds(2)).thenReturn(List.of(stale, next));
    doThrow(new OperationFencingException("stale"))
        .when(paymentOperationService)
        .processJob(eq(stale), anyString());

    worker.recover();

    verify(paymentOperationService).processJob(eq(next), anyString());
  }
}
