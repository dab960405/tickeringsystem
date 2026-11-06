package com.portfolio.ticketing.auth.service;

import com.portfolio.ticketing.auth.api.TokenResponse;
import com.portfolio.ticketing.common.exception.AuthenticationFailedException;
import com.portfolio.ticketing.user.domain.User;
import com.portfolio.ticketing.user.service.UserService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

  private final UserService userService;
  private final PasswordEncoder passwordEncoder;
  private final JwtTokenService tokenService;
  private final LoginRateLimiter loginRateLimiter;

  public AuthService(
      UserService userService,
      PasswordEncoder passwordEncoder,
      JwtTokenService tokenService,
      LoginRateLimiter loginRateLimiter) {
    this.userService = userService;
    this.passwordEncoder = passwordEncoder;
    this.tokenService = tokenService;
    this.loginRateLimiter = loginRateLimiter;
  }

  public TokenResponse login(String email, String rawPassword, String remoteAddress) {
    loginRateLimiter.check(remoteAddress, email);
    User user =
        userService
            .findByEmail(email)
            .orElseThrow(() -> new AuthenticationFailedException("Invalid email or password"));
    if (!user.isEnabled() || !passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
      throw new AuthenticationFailedException("Invalid email or password");
    }
    return tokenService.issueTokens(user);
  }

  public TokenResponse refresh(String refreshToken) {
    return tokenService.refresh(refreshToken);
  }

  public void logout(String refreshToken) {
    tokenService.logout(refreshToken);
  }
}
