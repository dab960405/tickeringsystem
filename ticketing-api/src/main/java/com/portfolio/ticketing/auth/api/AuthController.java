package com.portfolio.ticketing.auth.api;

import com.portfolio.ticketing.auth.service.AuthService;
import com.portfolio.ticketing.user.api.UserResponse;
import com.portfolio.ticketing.user.domain.User;
import com.portfolio.ticketing.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Registration and access token management")
public class AuthController {

  private final UserService userService;
  private final AuthService authService;

  public AuthController(UserService userService, AuthService authService) {
    this.userService = userService;
    this.authService = authService;
  }

  @PostMapping("/register")
  @Operation(summary = "Register a user with the USER role")
  @ApiResponse(responseCode = "201", description = "User registered")
  @ApiResponse(responseCode = "400", description = "Validation error")
  @ApiResponse(responseCode = "409", description = "Email already registered")
  public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
    User user = userService.register(request.email(), request.password(), request.fullName());
    UserResponse response = UserResponse.from(user);
    URI location =
        ServletUriComponentsBuilder.fromCurrentContextPath()
            .path("/api/v1/users/{id}")
            .buildAndExpand(response.id())
            .toUri();
    return ResponseEntity.created(location).body(response);
  }

  @PostMapping("/login")
  @Operation(summary = "Authenticate and return access and refresh tokens")
  @ApiResponse(responseCode = "200", description = "Authentication succeeded")
  @ApiResponse(responseCode = "400", description = "Validation error")
  @ApiResponse(responseCode = "401", description = "Invalid credentials")
  @ApiResponse(responseCode = "429", description = "Too many login attempts")
  public TokenResponse login(
      @Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
    return authService.login(request.email(), request.password(), httpRequest.getRemoteAddr());
  }

  @PostMapping("/refresh")
  @Operation(summary = "Renew an access token using a refresh token")
  @ApiResponse(responseCode = "200", description = "Access token renewed")
  @ApiResponse(responseCode = "400", description = "Validation error")
  @ApiResponse(responseCode = "401", description = "Invalid or revoked refresh token")
  public TokenResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
    return authService.refresh(request.refreshToken());
  }

  @PostMapping("/logout")
  @SecurityRequirement(name = "bearerAuth")
  @Operation(summary = "Revoke a refresh token")
  @ApiResponse(responseCode = "204", description = "Refresh token revoked")
  @ApiResponse(responseCode = "400", description = "Validation error")
  @ApiResponse(responseCode = "401", description = "Invalid refresh token")
  public ResponseEntity<Void> logout(@Valid @RequestBody RefreshTokenRequest request) {
    authService.logout(request.refreshToken());
    return ResponseEntity.noContent().build();
  }
}
