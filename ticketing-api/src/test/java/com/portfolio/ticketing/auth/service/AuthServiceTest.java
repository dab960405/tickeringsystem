package com.portfolio.ticketing.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.ticketing.auth.api.TokenResponse;
import com.portfolio.ticketing.common.exception.AuthenticationFailedException;
import com.portfolio.ticketing.user.domain.User;
import com.portfolio.ticketing.user.domain.UserRole;
import com.portfolio.ticketing.user.service.UserService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

  @Mock private UserService userService;
  @Mock private PasswordEncoder passwordEncoder;
  @Mock private JwtTokenService tokenService;
  @Mock private LoginRateLimiter loginRateLimiter;
  @InjectMocks private AuthService authService;

  @Test
  void should_issueTokens_when_credentialsAreValid() {
    User user = new User("user@example.com", "hash", "User", UserRole.USER);
    TokenResponse expected = TokenResponse.bearer("access", "refresh", 900);
    when(userService.findByEmail("user@example.com")).thenReturn(Optional.of(user));
    when(passwordEncoder.matches("Password123", "hash")).thenReturn(true);
    when(tokenService.issueTokens(user)).thenReturn(expected);

    TokenResponse response = authService.login("user@example.com", "Password123", "127.0.0.1");

    assertThat(response).isEqualTo(expected);
    verify(loginRateLimiter).check("127.0.0.1", "user@example.com");
  }

  @Test
  void should_rejectInvalidPassword_withoutIssuingTokens() {
    User user = new User("user@example.com", "hash", "User", UserRole.USER);
    when(userService.findByEmail("user@example.com")).thenReturn(Optional.of(user));
    when(passwordEncoder.matches("wrong", "hash")).thenReturn(false);

    assertThatThrownBy(() -> authService.login("user@example.com", "wrong", "127.0.0.1"))
        .isInstanceOf(AuthenticationFailedException.class)
        .hasMessage("Invalid email or password");
  }

  @Test
  void should_useSameError_when_emailDoesNotExist() {
    when(userService.findByEmail("missing@example.com")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> authService.login("missing@example.com", "wrong", "127.0.0.1"))
        .isInstanceOf(AuthenticationFailedException.class)
        .hasMessage("Invalid email or password");
  }

  @Test
  void should_delegateRefreshAndLogout() {
    TokenResponse expected = TokenResponse.bearer("access", "refresh", 900);
    when(tokenService.refresh("refresh")).thenReturn(expected);

    assertThat(authService.refresh("refresh")).isEqualTo(expected);
    authService.logout("refresh");

    verify(tokenService).logout("refresh");
  }
}
