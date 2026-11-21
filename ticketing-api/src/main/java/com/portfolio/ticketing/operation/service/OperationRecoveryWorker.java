package com.portfolio.ticketing.operation.service;

import com.portfolio.ticketing.operation.config.OperationProperties;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    prefix = "ticketing.operation",
    name = "recovery-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class OperationRecoveryWorker {

  private static final Logger log = LoggerFactory.getLogger(OperationRecoveryWorker.class);

  private final OperationJobService jobService;
  private final PaymentOperationService paymentOperationService;
  private final OperationProperties properties;
  private final String workerId = "recovery-" + UUID.randomUUID();

  public OperationRecoveryWorker(
      OperationJobService jobService,
      PaymentOperationService paymentOperationService,
      OperationProperties properties) {
    this.jobService = jobService;
    this.paymentOperationService = paymentOperationService;
    this.properties = properties;
  }

  @Scheduled(fixedDelayString = "${ticketing.operation.recovery-interval:1s}")
  public void recover() {
    for (UUID jobId : jobService.findRecoverableJobIds(properties.getBatchSize())) {
      try {
        paymentOperationService.processJob(jobId, workerId);
      } catch (OperationFencingException exception) {
        log.debug("Stale operation execution ignored for job {}", jobId);
      } catch (RuntimeException exception) {
        log.warn("Operation recovery failed for job {}: {}", jobId, exception.getClass().getName());
      }
    }
  }
}
