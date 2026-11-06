package com.portfolio.ticketing.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.ticketing.common.exception.DuplicateResourceException;
import com.portfolio.ticketing.user.domain.User;
import com.portfolio.ticketing.user.domain.UserRole;
import com.portfolio.ticketing.user.repository.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

  @Mock private UserRepository userRepository;
  @Mock private PasswordEncoder passwordEncoder;
  @InjectMocks private UserService userService;

  @Test
  void should_normalizeEmailAndHashPassword_when_registering() {
    when(userRepository.existsByEmailIgnoreCase("user@example.com")).thenReturn(false);
    when(passwordEncoder.encode("Password123")).thenReturn("bcrypt-hash");
    when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

    User user = userService.register(" User@Example.COM ", "Password123", " Example User ");

    ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
    verify(userRepository).save(captor.capture());
    assertThat(captor.getValue().getEmail()).isEqualTo("user@example.com");
    assertThat(captor.getValue().getPasswordHash()).isEqualTo("bcrypt-hash");
    assertThat(user.getFullName()).isEqualTo("Example User");
    assertThat(user.getRole()).isEqualTo(UserRole.USER);
  }

  @Test
  void should_rejectDuplicateEmail_when_registering() {
    when(userRepository.existsByEmailIgnoreCase("user@example.com")).thenReturn(true);

    assertThatThrownBy(() -> userService.register("user@example.com", "Password123", "User"))
        .isInstanceOf(DuplicateResourceException.class);
  }

  @Test
  void should_findUserUsingNormalizedEmail() {
    User user = new User("user@example.com", "hash", "User", UserRole.USER);
    when(userRepository.findByEmailIgnoreCase("user@example.com")).thenReturn(Optional.of(user));

    assertThat(userService.findByEmail(" USER@EXAMPLE.COM ")).contains(user);
  }
}
