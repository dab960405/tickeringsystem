package com.portfolio.ticketing.user.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.ticketing.common.config.JpaAuditingConfig;
import com.portfolio.ticketing.support.AbstractIntegrationTest;
import com.portfolio.ticketing.user.domain.User;
import com.portfolio.ticketing.user.domain.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

@DataJpaTest
@Import(JpaAuditingConfig.class)
class UserRepositoryIT extends AbstractIntegrationTest {

  @Autowired private UserRepository userRepository;

  @Test
  void should_findUserByEmailIgnoringCase() {
    User saved =
        userRepository.saveAndFlush(
            new User("person@example.com", "$2a$10$hash", "Person Example", UserRole.USER));

    assertThat(saved.getCreatedAt()).isNotNull();
    assertThat(saved.getUpdatedAt()).isNotNull();
    assertThat(userRepository.findByEmailIgnoreCase("PERSON@EXAMPLE.COM"))
        .get()
        .extracting(User::getFullName, User::getRole)
        .containsExactly("Person Example", UserRole.USER);
  }
}
