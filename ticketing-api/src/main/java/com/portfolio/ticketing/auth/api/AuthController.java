package com.portfolio.ticketing.auth.api;

import com.portfolio.ticketing.user.api.UserResponse;
import com.portfolio.ticketing.user.domain.User;
import com.portfolio.ticketing.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
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

  public AuthController(UserService userService) {
    this.userService = userService;
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
}
