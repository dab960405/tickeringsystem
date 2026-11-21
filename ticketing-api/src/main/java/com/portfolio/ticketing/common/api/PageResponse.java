package com.portfolio.ticketing.common.api;

import java.util.List;
import org.springframework.data.domain.Page;

/** Stable pagination contract, decoupled from the Spring Data {@link Page} JSON layout. */
public record PageResponse<T>(
    List<T> content, int page, int size, long totalElements, int totalPages) {

  public static <T> PageResponse<T> from(Page<T> page) {
    return new PageResponse<>(
        page.getContent(),
        page.getNumber(),
        page.getSize(),
        page.getTotalElements(),
        page.getTotalPages());
  }
}
