package com.portfolio.ticketing.common.exception;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portfolio.ticketing.auth.config.SecurityConfig;
import com.portfolio.ticketing.auth.config.SecurityErrorHandler;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(
    controllers = GlobalExceptionHandlerTest.FailingController.class,
    properties = "ticketing.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")
@Import({
  GlobalExceptionHandlerTest.FailingController.class,
  SecurityConfig.class,
  SecurityErrorHandler.class
})
@WithMockUser
class GlobalExceptionHandlerTest {

  @Autowired private MockMvc mockMvc;

  record SampleRequest(@NotBlank(message = "name is required") String name) {}

  @RestController
  static class FailingController {

    private static final Map<String, Supplier<RuntimeException>> FAILURES =
        Map.of(
            "not-found", () -> new ResourceNotFoundException("Event not found"),
            "stock", () -> new InsufficientStockException("Not enough tickets"),
            "transition", () -> new InvalidStateTransitionException("Invalid transition"),
            "duplicate", () -> new DuplicateResourceException("Already exists"),
            "rule", () -> new BusinessRuleViolationException("Rule broken"),
            "unexpected", () -> new IllegalStateException("secret internal detail"));

    @GetMapping("/test/fail/{kind}")
    void fail(@PathVariable String kind) {
      throw FAILURES.get(kind).get();
    }

    @PostMapping("/test/validate")
    void validate(@Valid @RequestBody SampleRequest request) {}
  }

  static Stream<Arguments> businessFailures() {
    return Stream.of(
        Arguments.of("not-found", 404),
        Arguments.of("stock", 409),
        Arguments.of("transition", 409),
        Arguments.of("duplicate", 409),
        Arguments.of("rule", 422),
        Arguments.of("unexpected", 500));
  }

  @ParameterizedTest
  @MethodSource("businessFailures")
  void should_mapToHttpStatus_when_exceptionIsThrown(String kind, int expectedStatus)
      throws Exception {
    mockMvc
        .perform(get("/test/fail/" + kind))
        .andExpect(status().is(expectedStatus))
        .andExpect(jsonPath("$.status").value(expectedStatus));
  }

  @Test
  void should_hideInternalMessage_when_unexpectedErrorOccurs() throws Exception {
    mockMvc
        .perform(get("/test/fail/unexpected"))
        .andExpect(jsonPath("$.detail").value("An unexpected error occurred"));
  }

  @Test
  void should_returnFieldErrors_when_validationFails() throws Exception {
    mockMvc
        .perform(
            post("/test/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.name").value("name is required"));
  }
}
