package com.portfolio.ticketing.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.portfolio.ticketing.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenApiDocsIT extends AbstractIntegrationTest {

  @Autowired private TestRestTemplate restTemplate;

  @Test
  void should_documentEveryPhaseOneEndpoint_when_openApiIsRequested() {
    ResponseEntity<JsonNode> response = restTemplate.getForEntity("/v3/api-docs", JsonNode.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode paths = response.getBody().path("paths");
    assertOperations(paths, "/api/v1/venues", "get", "post");
    assertOperations(paths, "/api/v1/venues/{id}", "get", "put", "delete");
    assertOperations(paths, "/api/v1/events", "get", "post");
    assertOperations(paths, "/api/v1/events/{id}", "get", "put");
    assertOperations(paths, "/api/v1/events/{id}/publish", "post");
    assertOperations(paths, "/api/v1/events/{id}/cancel", "post");
    assertOperations(paths, "/api/v1/events/{eventId}/ticket-types", "post");
    assertOperations(paths, "/api/v1/ticket-types/{id}", "put");
    assertOperations(paths, "/api/v1/auth/register", "post");
    assertOperations(paths, "/api/v1/auth/login", "post");
    assertOperations(paths, "/api/v1/auth/refresh", "post");
    assertOperations(paths, "/api/v1/auth/logout", "post");
    assertThat(
            response
                .getBody()
                .path("components")
                .path("securitySchemes")
                .path("bearerAuth")
                .path("scheme")
                .asText())
        .isEqualTo("bearer");
  }

  @Test
  void should_serveSwaggerUi_when_documentedPathIsRequested() {
    ResponseEntity<String> response =
        restTemplate.getForEntity("/swagger-ui/index.html", String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  private static void assertOperations(JsonNode paths, String path, String... methods) {
    JsonNode operations = paths.path(path);
    assertThat(operations.isMissingNode()).as("path %s is documented", path).isFalse();
    for (String method : methods) {
      JsonNode operation = operations.path(method);
      assertThat(operation.isMissingNode()).as("%s %s is documented", method, path).isFalse();
      assertThat(operation.path("summary").asText())
          .as("summary of %s %s", method, path)
          .isNotBlank();
    }
  }
}
