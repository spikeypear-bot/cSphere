package com.example.connect_sphere.venueissue.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.example.connect_sphere.venue.entity.Venue;
import com.example.connect_sphere.venue.repository.VenueRepository;
import com.example.connect_sphere.venuebooking.entity.VenueBookingStatus;
import com.example.connect_sphere.venuebooking.repository.VenueBookingRecordRepository;
import com.example.connect_sphere.venueissue.dto.CoordinatorVenueOperationalIssueDto;
import com.example.connect_sphere.venueissue.entity.VenueOperationalIssue;
import com.example.connect_sphere.venueissue.repository.VenueOperationalIssueRepository;

@Tag("unit")
class CoordinatorVenueOperationalIssueServiceTest {

    @Mock private VenueOperationalIssueRepository issues;
    @Mock private VenueRepository venues;
    @Mock private VenueBookingRecordRepository bookings;

    private VenueOperationalIssueService service;
    private final UUID venueId = UUID.randomUUID();
    private final UUID coordinatorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new VenueOperationalIssueService(issues, venues, bookings);
    }

    @Test
    void returnsOverlappingIssuesForApprovedManagedBookings() {
        UUID eventId = UUID.randomUUID();
        var from = OffsetDateTime.parse("2027-03-10T09:00:00+08:00");
        var until = OffsetDateTime.parse("2027-03-10T12:00:00+08:00");
        var booking = mock(VenueBookingRecordRepository.CoordinatorBooking.class);
        when(booking.getVenueId()).thenReturn(venueId);
        when(booking.getEventId()).thenReturn(eventId);
        when(booking.getEventName()).thenReturn("Town Hall");
        when(booking.getStartDatetime()).thenReturn(from);
        when(booking.getEndDatetime()).thenReturn(until);
        when(bookings.findApprovedForCoordinator(coordinatorId, VenueBookingStatus.approved))
                .thenReturn(List.of(booking));

        var venue = mock(Venue.class);
        when(venue.getVenueId()).thenReturn(venueId);
        when(venue.getVenueAddress()).thenReturn("1 Harbour Road");
        when(venues.findAllById(any())).thenReturn(List.of(venue));

        var issue = new VenueOperationalIssue(
                venueId, "Air conditioning failure", from.minusHours(1), until.minusHours(1), coordinatorId);
        when(issues.findByVenueIdInOrderByAffectedFromAscCreatedAtDesc(List.of(venueId)))
                .thenReturn(List.of(issue));

        List<CoordinatorVenueOperationalIssueDto> result = service.listForCoordinator(coordinatorId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).venueAddress()).isEqualTo("1 Harbour Road");
        assertThat(result.get(0).overlappingEvents()).extracting("eventName")
                .containsExactly("Town Hall");
    }

    @Test
    void hidesIssuesWhenCoordinatorHasNoApprovedManagedBookings() {
        when(bookings.findApprovedForCoordinator(coordinatorId, VenueBookingStatus.approved))
                .thenReturn(List.of());

        assertThat(service.listForCoordinator(coordinatorId)).isEmpty();
        verify(issues, org.mockito.Mockito.never())
                .findByVenueIdInOrderByAffectedFromAscCreatedAtDesc(any());
    }
}
