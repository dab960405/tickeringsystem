package com.portfolio.ticketing.user.api;

import com.portfolio.ticketing.user.domain.User;
import com.portfolio.ticketing.user.domain.UserRole;
import java.time.Instant;
import java.util.UUID;

public record UserResponse(
    UUID id, String email, String fullName, UserRole role, Instant createdAt) {

  public static UserResponse from(User user) {
    return new UserResponse(
        user.getId(), user.getEmail(), user.getFullName(), user.getRole(), user.getCreatedAt());
  }
}
