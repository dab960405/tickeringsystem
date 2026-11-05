package com.portfolio.ticketing.venue.service;

import com.portfolio.ticketing.common.api.PageResponse;
import com.portfolio.ticketing.common.exception.ResourceNotFoundException;
import com.portfolio.ticketing.venue.api.VenueRequest;
import com.portfolio.ticketing.venue.api.VenueResponse;
import com.portfolio.ticketing.venue.domain.Venue;
import com.portfolio.ticketing.venue.repository.VenueRepository;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class VenueService {

  private final VenueRepository venueRepository;

  public VenueService(VenueRepository venueRepository) {
    this.venueRepository = venueRepository;
  }

  public PageResponse<VenueResponse> list(Pageable pageable) {
    return PageResponse.from(venueRepository.findAll(pageable).map(VenueResponse::from));
  }

  public VenueResponse get(UUID id) {
    return VenueResponse.from(findVenue(id));
  }

  /** Used by other modules (e.g. events) that need the venue entity reference. */
  public Venue findVenue(UUID id) {
    return venueRepository
        .findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("Venue " + id + " not found"));
  }

  @Transactional
  public VenueResponse create(VenueRequest request) {
    Venue venue = new Venue(request.name(), request.city(), request.address(), request.capacity());
    return VenueResponse.from(venueRepository.save(venue));
  }

  @Transactional
  public VenueResponse update(UUID id, VenueRequest request) {
    Venue venue = findVenue(id);
    venue.update(request.name(), request.city(), request.address(), request.capacity());
    return VenueResponse.from(venueRepository.saveAndFlush(venue));
  }

  @Transactional
  public void delete(UUID id) {
    venueRepository.delete(findVenue(id));
  }
}
