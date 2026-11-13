package com.portfolio.ticketing.operation.service;

import com.portfolio.ticketing.operation.config.OperationProperties;
import java.time.Duration;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class OperationTransactionExecutor {

  private final PlatformTransactionManager transactionManager;
  private final JdbcTemplate jdbcTemplate;
  private final OperationProperties properties;

  public OperationTransactionExecutor(
      PlatformTransactionManager transactionManager,
      JdbcTemplate jdbcTemplate,
      OperationProperties properties) {
    this.transactionManager = transactionManager;
    this.jdbcTemplate = jdbcTemplate;
    this.properties = properties;
  }

  public <T> T rx(Supplier<T> action) {
    return execute(properties.getTxRxTimeout(), action);
  }

  public <T> T claim(Supplier<T> action) {
    return execute(properties.getTxClaimTimeout(), action);
  }

  public <T> T apply(Supplier<T> action) {
    return execute(properties.getTxApplyTimeout(), action);
  }

  public <T> T project(Supplier<T> action) {
    return execute(properties.getTxProjectTimeout(), action);
  }

  private <T> T execute(Duration timeout, Supplier<T> action) {
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transaction.setTimeout(Math.toIntExact(timeout.toSeconds()));
    return transaction.execute(ignored -> {
      setTimeout("lock_timeout", properties.getLockTimeout());
      setTimeout("statement_timeout", properties.statementTimeout(timeout));
      return action.get();
    });
  }

  private void setTimeout(String name, Duration duration) {
    jdbcTemplate.queryForObject(
        "SELECT set_config(?, ?, true)", String.class, name, duration.toMillis() + "ms");
  }
}
