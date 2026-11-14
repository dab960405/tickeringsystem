package com.portfolio.ticketing.event.service;

import com.portfolio.ticketing.venue.domain.VenueUpdatedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class VenueEventCacheInvalidationListener {

  private final EventCatalogCacheService eventCatalogCacheService;

  public VenueEventCacheInvalidationListener(EventCatalogCacheService eventCatalogCacheService) {
    this.eventCatalogCacheService = eventCatalogCacheService;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onVenueUpdated(VenueUpdatedEvent event) {
    eventCatalogCacheService.evictAll();
  }
}
