package com.portfolio.ticketing.event.service;

import com.portfolio.ticketing.common.exception.ResourceNotFoundException;
import com.portfolio.ticketing.event.repository.EventRepository;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service("eventAuthorizationService")
@Transactional(readOnly = true)
public class EventAuthorizationService {

  private final EventRepository eventRepository;

  public EventAuthorizationService(EventRepository eventRepository) {
    this.eventRepository = eventRepository;
  }

  public boolean canManage(UUID eventId, Authentication authentication) {
    if (authentication == null || !authentication.isAuthenticated()) {
      return false;
    }
    if (hasRole(authentication, "ROLE_ADMIN")) {
      return true;
    }
    if (!hasRole(authentication, "ROLE_ORGANIZER")) {
      return false;
    }
    return eventRepository
        .findById(eventId)
        .map(
            event -> {
              if (!event.isOwnedBy(subject(authentication))) {
                // Conceal the existence of resources owned by another organizer.
                throw new ResourceNotFoundException("Event " + eventId + " not found");
              }
              return true;
            })
        // Let the business service return 404 for an unknown event.
        .orElse(true);
  }

  private static UUID subject(Authentication authentication) {
    try {
      return UUID.fromString(authentication.getName());
    } catch (IllegalArgumentException exception) {
      return null;
    }
  }

  private static boolean hasRole(Authentication authentication, String role) {
    return authentication.getAuthorities().stream()
        .map(GrantedAuthority::getAuthority)
        .anyMatch(role::equals);
  }
}
