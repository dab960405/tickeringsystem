package com.portfolio.ticketing.common.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.portfolio.ticketing.support.AbstractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class DataIntegrityLoggingIT extends AbstractIntegrationTest {
  @Autowired private JdbcTemplate jdbc;

  @Test
  void should_hideEmailInApplicationLog_when_postgresRejectsDuplicateEmail() {
    String email = "audit-" + UUID.randomUUID() + "@example.com";
    String insert =
        "INSERT INTO users (email, password_hash, full_name, role) VALUES (?, 'hash', 'Audit', 'USER')";
    jdbc.update(insert, email);
    Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      DataIntegrityViolationException failure =
          catchThrowableOfType(
              DataIntegrityViolationException.class, () -> jdbc.update(insert, email));
      assertThat(failure).isNotNull();
      assertThat(failure.getMostSpecificCause().getMessage()).contains(email);
      assertThat(new GlobalExceptionHandler().handleDataIntegrity(failure).getStatus())
          .isEqualTo(409);
      assertThat(appender.list).hasSize(1);
      assertThat(appender.list.getFirst().getFormattedMessage())
          .doesNotContain(email)
          .contains("Data integrity violation");
      assertThat(appender.list.getFirst().getThrowableProxy()).isNull();
    } finally {
      logger.detachAppender(appender);
      appender.stop();
      jdbc.update("DELETE FROM users WHERE email = ?", email);
    }
  }
}
