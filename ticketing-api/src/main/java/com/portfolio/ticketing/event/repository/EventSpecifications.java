package com.portfolio.ticketing.event.repository;

import com.portfolio.ticketing.event.domain.Event;
import com.portfolio.ticketing.event.domain.EventStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

/** Builds the public catalog query; every filter is optional and combined with AND. */
public final class EventSpecifications {

  private EventSpecifications() {}

  public static Specification<Event> publicCatalog(EventFilter filter) {
    List<Specification<Event>> specs = new ArrayList<>();
    specs.add((root, query, cb) -> cb.equal(root.get("status"), EventStatus.PUBLISHED));
    if (hasText(filter.q())) {
      String pattern = "%" + escapeLike(filter.q().trim().toLowerCase()) + "%";
      specs.add((root, query, cb) -> cb.like(cb.lower(root.get("title")), pattern, '\\'));
    }
    if (hasText(filter.category())) {
      specs.add((root, query, cb) ->
          cb.equal(cb.lower(root.get("category")), filter.category().trim().toLowerCase()));
    }
    if (hasText(filter.city())) {
      specs.add((root, query, cb) ->
          cb.equal(cb.lower(root.get("venue").get("city")), filter.city().trim().toLowerCase()));
    }
    Instant from = filter.from();
    if (from != null) {
      specs.add((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("startsAt"), from));
    }
    Instant to = filter.to();
    if (to != null) {
      specs.add((root, query, cb) -> cb.lessThan(root.get("startsAt"), to));
    }
    return Specification.allOf(specs);
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  // User input must not be able to inject LIKE wildcards.
  private static String escapeLike(String value) {
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }
}
