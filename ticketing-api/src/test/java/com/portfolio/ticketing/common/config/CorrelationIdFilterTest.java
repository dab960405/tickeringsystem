package com.portfolio.ticketing.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {

  private final CorrelationIdFilter filter = new CorrelationIdFilter();

  @Test
  void should_generateCorrelationIdAndClearMdc_when_requestHasNoHeader() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(
        request,
        response,
        (servletRequest, servletResponse) -> {
          String id = MDC.get("correlationId");
          assertThat(id).matches("[0-9a-f-]{36}");
          assertThat(response.getHeader("X-Correlation-Id")).isEqualTo(id);
        });

    assertThat(MDC.get("correlationId")).isNull();
  }

  @Test
  void should_reuseSafeClientCorrelationId() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("X-Correlation-Id", "purchase-123.A_B");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(
        request,
        response,
        (servletRequest, servletResponse) ->
            assertThat(MDC.get("correlationId")).isEqualTo("purchase-123.A_B"));

    assertThat(response.getHeader("X-Correlation-Id")).isEqualTo("purchase-123.A_B");
    assertThat(MDC.get("correlationId")).isNull();
  }

  @Test
  void should_rejectUntrustedCorrelationIdsIncludingLogInjectionAndOversize() throws Exception {
    for (String badId : new String[] {"malicious\r\nfake-log-entry", "x".repeat(65), "bad id"}) {
      MockHttpServletRequest request = new MockHttpServletRequest();
      request.addHeader("X-Correlation-Id", badId);
      MockHttpServletResponse response = new MockHttpServletResponse();

      filter.doFilter(
          request,
          response,
          (servletRequest, servletResponse) -> {
            String id = MDC.get("correlationId");
            assertThat(id).matches("[0-9a-f-]{36}").isNotEqualTo(badId);
            assertThat(response.getHeader("X-Correlation-Id")).isEqualTo(id);
          });

      assertThat(MDC.get("correlationId")).isNull();
    }
  }

  @Test
  void should_restorePreviousMdc_when_filterChainThrows() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();
    MDC.put("correlationId", "outer-request");
    try {
      assertThrows(
          IOException.class,
          () ->
              filter.doFilter(
                  request,
                  response,
                  (servletRequest, servletResponse) -> {
                    assertThat(MDC.get("correlationId")).isNotEqualTo("outer-request");
                    throw new IOException("test failure");
                  }));
      assertThat(MDC.get("correlationId")).isEqualTo("outer-request");
    } finally {
      MDC.remove("correlationId");
    }
  }
}
