package com.portfolio.ticketing.auth.api;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portfolio.ticketing.common.exception.DuplicateResourceException;
import com.portfolio.ticketing.user.domain.User;
import com.portfolio.ticketing.user.domain.UserRole;
import com.portfolio.ticketing.user.service.UserService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AuthController.class)
class AuthControllerTest {

  private static final UUID ID = UUID.fromString("0b5e6f1a-3c2d-4e5f-8a9b-1c2d3e4f5a6b");

  @Autowired private MockMvc mockMvc;
  @MockitoBean private UserService userService;

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
