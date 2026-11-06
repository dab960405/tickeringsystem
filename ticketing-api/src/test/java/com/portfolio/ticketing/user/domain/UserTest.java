package com.portfolio.ticketing.user.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UserTest {

  @Test
  void should_createEnabledUserWithRoleAndCredentials() {
    User user = new User("user@example.com", "hashed", "Example User", UserRole.USER);

    assertThat(user)
        .extracting(User::getEmail, User::getPasswordHash, User::getFullName, User::getRole)
        .containsExactly("user@example.com", "hashed", "Example User", UserRole.USER);
    assertThat(user.isEnabled()).isTrue();
  }
}
