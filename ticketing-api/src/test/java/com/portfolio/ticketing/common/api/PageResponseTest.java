package com.portfolio.ticketing.common.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

class PageResponseTest {

  @Test
  void should_copyPaginationMetadata_when_builtFromPage() {
    PageImpl<String> page = new PageImpl<>(List.of("a", "b"), PageRequest.of(1, 2), 5);

    PageResponse<String> response = PageResponse.from(page);

    assertThat(response).isEqualTo(new PageResponse<>(List.of("a", "b"), 1, 2, 5, 3));
  }
}
