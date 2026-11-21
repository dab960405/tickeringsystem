package com.portfolio.ticketing.auth.api;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portfolio.ticketing.auth.config.SecurityConfig;
import com.portfolio.ticketing.auth.config.SecurityErrorHandler;
import com.portfolio.ticketing.auth.service.AuthService;
import com.portfolio.ticketing.common.exception.AuthenticationFailedException;
import com.portfolio.ticketing.common.exception.DuplicateResourceException;
import com.portfolio.ticketing.user.domain.User;
import com.portfolio.ticketing.user.domain.UserRole;
import com.portfolio.ticketing.user.service.UserService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
    value = AuthController.class,
    properties = "ticketing.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")
@Import({SecurityConfig.class, SecurityErrorHandler.class})
class AuthControllerTest {

  private static final UUID ID = UUID.fromString("0b5e6f1a-3c2d-4e5f-8a9b-1c2d3e4f5a6b");

  @Autowired private MockMvc mockMvc;
  @MockitoBean private UserService userService;
  @MockitoBean private AuthService authService;

  @Test
  void should_return201WithoutPassword_when_registrationIsValid() throws Exception {
    User user = new User("user@example.com", "hash", "Example User", UserRole.USER);
    ReflectionTestUtils.setField(user, "id", ID);
    when(userService.register(anyString(), anyString(), anyString())).thenReturn(user);

    mockMvc
        .perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"email\":\"user@example.com\",\"password\":\"Password123\",\"fullName\":\"Example User\"}"))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", "http://localhost/api/v1/users/" + ID))
        .andExpect(jsonPath("$.email").value("user@example.com"))
        .andExpect(jsonPath("$.role").value("USER"))
        .andExpect(jsonPath("$.passwordHash").doesNotExist())
        .andExpect(jsonPath("$.password").doesNotExist());
  }

  @Test
  void should_return400WithFieldErrors_when_passwordDoesNotMeetPolicy() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"email\":\"user@example.com\",\"password\":\"password\",\"fullName\":\"Example User\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.password").exists());
  }

  @Test
  void should_returnTokens_when_loginIsValid() throws Exception {
    when(authService.login("user@example.com", "Password123", "127.0.0.1"))
        .thenReturn(TokenResponse.bearer("access", "refresh", 900));

    mockMvc
        .perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"user@example.com\",\"password\":\"Password123\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessToken").value("access"))
        .andExpect(jsonPath("$.refreshToken").value("refresh"))
        .andExpect(jsonPath("$.tokenType").value("Bearer"));
  }

  @Test
  void should_return401_when_credentialsAreInvalid() throws Exception {
    when(authService.login("user@example.com", "wrong", "127.0.0.1"))
        .thenThrow(new AuthenticationFailedException("Invalid email or password"));

    mockMvc
        .perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"user@example.com\",\"password\":\"wrong\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void should_return204_when_logoutRevokesToken() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/auth/logout")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"refresh\"}"))
        .andExpect(status().isNoContent());
  }

  @Test
  void should_return409_when_emailIsAlreadyRegistered() throws Exception {
    when(userService.register(anyString(), anyString(), anyString()))
        .thenThrow(new DuplicateResourceException("duplicate"));

    mockMvc
        .perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"email\":\"user@example.com\",\"password\":\"Password123\",\"fullName\":\"Example User\"}"))
        .andExpect(status().isConflict());
  }
}
