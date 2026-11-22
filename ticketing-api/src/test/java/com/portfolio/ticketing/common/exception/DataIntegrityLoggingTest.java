package com.portfolio.ticketing.common.exception;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;

class DataIntegrityLoggingTest {
  @Test
  void should_notLogRejectedValues_when_databaseReportsConstraintViolation() {
    Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      var failure =
          new DataIntegrityViolationException(
              "insert failed",
              new SQLException(
                  "Key (email)=(private@example.com) already exists; token=private-token; password=private-password",
                  "23505"));
      var problem = new GlobalExceptionHandler().handleDataIntegrity(failure);
      assertThat(problem.getStatus()).isEqualTo(409);
      assertThat(problem.getDetail()).isEqualTo("The request conflicts with existing data");
      assertThat(appender.list).hasSize(1);
      assertThat(appender.list.getFirst().getFormattedMessage())
          .doesNotContain("private@example.com", "private-token", "private-password")
          .contains("Data integrity violation");
      assertThat(appender.list.getFirst().getThrowableProxy()).isNull();
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }
}
