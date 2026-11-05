package com.portfolio.ticketing.venue.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.ticketing.common.api.PageResponse;
import com.portfolio.ticketing.common.exception.ResourceNotFoundException;
import com.portfolio.ticketing.venue.api.VenueRequest;
import com.portfolio.ticketing.venue.api.VenueResponse;
import com.portfolio.ticketing.venue.domain.Venue;
import com.portfolio.ticketing.venue.repository.VenueRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class VenueServiceTest {

  private static final VenueRequest REQUEST = new VenueRequest("Arena", "Lima", "Main St 1", 500);

  @Mock private VenueRepository venueRepository;
  @InjectMocks private VenueService venueService;

  @Test
  void should_returnMappedResponse_when_creatingVenue() {
    when(venueRepository.save(any(Venue.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    VenueResponse response = venueService.create(REQUEST);

    assertThat(response.name()).isEqualTo("Arena");
  }

  @Test
  void should_throwNotFound_when_venueDoesNotExist() {
    UUID id = UUID.randomUUID();
    when(venueRepository.findById(id)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> venueService.get(id)).isInstanceOf(ResourceNotFoundException.class);
  }

  @Test
  void should_returnVenue_when_venueExists() {
    UUID id = UUID.randomUUID();
    when(venueRepository.findById(id))
        .thenReturn(Optional.of(new Venue("Arena", "Lima", "Main St 1", 500)));

    assertThat(venueService.get(id).city()).isEqualTo("Lima");
  }

  @Test
  void should_applyChanges_when_updatingVenue() {
    UUID id = UUID.randomUUID();
    when(venueRepository.findById(id))
        .thenReturn(Optional.of(new Venue("Old", "Cusco", "Old St", 10)));
    when(venueRepository.saveAndFlush(any(Venue.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    VenueResponse response = venueService.update(id, REQUEST);

    assertThat(response)
        .extracting(VenueResponse::name, VenueResponse::capacity)
        .containsExactly("Arena", 500);
  }

  @Test
  void should_deleteVenue_when_venueExists() {
    UUID id = UUID.randomUUID();
    Venue venue = new Venue("Arena", "Lima", "Main St 1", 500);
    when(venueRepository.findById(id)).thenReturn(Optional.of(venue));

    venueService.delete(id);

    verify(venueRepository).delete(venue);
  }

  @Test
  void should_wrapPage_when_listingVenues() {
    PageRequest pageable = PageRequest.of(0, 10);
    when(venueRepository.findAll(pageable))
        .thenReturn(
            new PageImpl<>(List.of(new Venue("Arena", "Lima", "Main St 1", 500)), pageable, 1));

    PageResponse<VenueResponse> page = venueService.list(pageable);

    assertThat(page)
        .extracting(PageResponse::totalElements, PageResponse::size)
        .containsExactly(1L, 10);
  }
}
