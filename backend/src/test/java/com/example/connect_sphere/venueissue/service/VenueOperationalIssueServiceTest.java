package com.example.connect_sphere.venueissue.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.example.connect_sphere.venue.repository.VenueRepository;
import com.example.connect_sphere.venueissue.dto.CreateVenueOperationalIssueRequest;
import com.example.connect_sphere.venueissue.entity.VenueOperationalIssue;
import com.example.connect_sphere.venueissue.repository.VenueOperationalIssueRepository;
import com.example.connect_sphere.venuebooking.repository.VenueBookingRecordRepository;

class VenueOperationalIssueServiceTest {

    @Mock private VenueOperationalIssueRepository issues;
    @Mock private VenueRepository venues;
    @Mock private VenueBookingRecordRepository bookings;

    private VenueOperationalIssueService service;
    private final UUID venueId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new VenueOperationalIssueService(issues, venues, bookings);
        when(issues.save(any(VenueOperationalIssue.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void createsAnIssueForAnExistingVenue() {
        when(venues.existsById(venueId)).thenReturn(true);
        var from = OffsetDateTime.parse("2027-03-10T09:00:00+08:00");
        var until = OffsetDateTime.parse("2027-03-10T12:00:00+08:00");

        var saved = service.create(venueId, userId,
                new CreateVenueOperationalIssueRequest("Air conditioning failure", from, until));

        assertThat(saved.venueId()).isEqualTo(venueId);
        assertThat(saved.description()).isEqualTo("Air conditioning failure");
        assertThat(saved.affectedFrom()).isEqualTo(from);
        assertThat(saved.affectedUntil()).isEqualTo(until);
        assertThat(saved.createdBy()).isEqualTo(userId);
        verify(issues).save(any(VenueOperationalIssue.class));
        org.mockito.Mockito.verifyNoInteractions(bookings);
    }

    @Test
    void trimsTheDescriptionBeforeSaving() {
        when(venues.existsById(venueId)).thenReturn(true);

        var saved = service.create(venueId, userId,
                new CreateVenueOperationalIssueRequest("  Renovation work  ", null, null));

        assertThat(saved.description()).isEqualTo("Renovation work");
    }

    @Test
    void rejectsBlankDescription() {
        when(venues.existsById(venueId)).thenReturn(true);

        assertThatThrownBy(() -> service.create(venueId, userId,
                new CreateVenueOperationalIssueRequest("  ", null, null)))
                .isInstanceOf(InvalidVenueOperationalIssueException.class)
                .hasMessage("Issue description is required.");
    }

    @Test
    void rejectsOnlyOnePeriodBoundary() {
        when(venues.existsById(venueId)).thenReturn(true);
        var from = OffsetDateTime.parse("2027-03-10T09:00:00+08:00");

        assertThatThrownBy(() -> service.create(venueId, userId,
                new CreateVenueOperationalIssueRequest("Maintenance", from, null)))
                .isInstanceOf(InvalidVenueOperationalIssueException.class)
                .hasMessage("Affected start and end date/time must be supplied together.");
    }

    @Test
    void rejectsAnEndThatIsNotAfterTheStart() {
        when(venues.existsById(venueId)).thenReturn(true);
        var from = OffsetDateTime.parse("2027-03-10T12:00:00+08:00");

        assertThatThrownBy(() -> service.create(venueId, userId,
                new CreateVenueOperationalIssueRequest("Maintenance", from, from)))
                .isInstanceOf(InvalidVenueOperationalIssueException.class)
                .hasMessage("Affected end date/time must be after the start date/time.");
    }

    @Test
    void rejectsAnUnknownVenue() {
        when(venues.existsById(venueId)).thenReturn(false);

        assertThatThrownBy(() -> service.create(venueId, userId,
                new CreateVenueOperationalIssueRequest("Maintenance", null, null)))
                .isInstanceOf(com.example.connect_sphere.venue.service.VenueNotFoundException.class);
    }

    @Test
    void listsIssuesForAnExistingVenue() {
        when(venues.existsById(venueId)).thenReturn(true);
        when(issues.findByVenueIdOrderByAffectedFromAscCreatedAtDesc(venueId)).thenReturn(List.of());

        assertThat(service.list(venueId)).isEmpty();
        verify(issues).findByVenueIdOrderByAffectedFromAscCreatedAtDesc(venueId);
    }

}
