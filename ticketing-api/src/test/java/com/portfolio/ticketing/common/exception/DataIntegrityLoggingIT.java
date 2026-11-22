package com.portfolio.ticketing.common.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.portfolio.ticketing.support.AbstractIntegrationTest;
import com.portfolio.ticketing.user.domain.User;
import com.portfolio.ticketing.user.domain.UserRole;
import com.portfolio.ticketing.user.repository.UserRepository;
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
  @Autowired private UserRepository users;

  @Test
  void should_notLogRejectedEmailFromJpaOrHibernateOnUniqueConflict() {
    String email = "private-" + UUID.randomUUID() + "@example.com";
    users.saveAndFlush(new User(email, "hash", "Private User", UserRole.USER));
    Logger rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    rootLogger.addAppender(appender);
    try {
      DataIntegrityViolationException failure =
          catchThrowableOfType(
              DataIntegrityViolationException.class,
              () -> users.saveAndFlush(new User(email, "hash", "Private User", UserRole.USER)));
      assertThat(failure).isNotNull();
      assertThat(appender.list)
          .allSatisfy(event -> assertThat(event.getFormattedMessage()).doesNotContain(email));
    } finally {
      rootLogger.detachAppender(appender);
      appender.stop();
      jdbc.update("DELETE FROM users WHERE email = ?", email);
    }
  }

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
