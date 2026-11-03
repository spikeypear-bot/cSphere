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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.example.connect_sphere.venue.repository.VenueRepository;
import com.example.connect_sphere.venueissue.dto.CreateVenueOperationalIssueRequest;
import com.example.connect_sphere.venueissue.entity.VenueOperationalIssue;
import com.example.connect_sphere.venueissue.repository.VenueOperationalIssueRepository;
import com.example.connect_sphere.venuebooking.repository.VenueBookingRecordRepository;

@Tag("unit")
@Tag("VS13")
@Tag("VS17")
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
        when(venues.findForUpdate(venueId)).thenReturn(java.util.Optional.of(new com.example.connect_sphere.venue.entity.Venue()));
        when(issues.saveAndFlush(any(VenueOperationalIssue.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("VS13 (happy): an issue is saved for an existing venue without touching its bookings")
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
        verify(issues).saveAndFlush(any(VenueOperationalIssue.class));
        org.mockito.Mockito.verifyNoInteractions(bookings);
    }

    @Test
    @DisplayName("VS13 (boundary): the issue description is saved without surrounding spaces")
    void trimsTheDescriptionBeforeSaving() {
        when(venues.existsById(venueId)).thenReturn(true);

        var saved = service.create(venueId, userId,
                new CreateVenueOperationalIssueRequest("  Renovation work  ", null, null));

        assertThat(saved.description()).isEqualTo("Renovation work");
    }

    @Test
    @DisplayName("VS13 (negative): a blank issue description is refused")
    void rejectsBlankDescription() {
        when(venues.existsById(venueId)).thenReturn(true);

        assertThatThrownBy(() -> service.create(venueId, userId,
                new CreateVenueOperationalIssueRequest("  ", null, null)))
                .isInstanceOf(InvalidVenueOperationalIssueException.class)
                .hasMessage("Issue description is required.");
    }

    @Test
    @DisplayName("VS13 (negative): an affected period with only a start is refused")
    void rejectsOnlyOnePeriodBoundary() {
        when(venues.existsById(venueId)).thenReturn(true);
        var from = OffsetDateTime.parse("2027-03-10T09:00:00+08:00");

        assertThatThrownBy(() -> service.create(venueId, userId,
                new CreateVenueOperationalIssueRequest("Maintenance", from, null)))
                .isInstanceOf(InvalidVenueOperationalIssueException.class)
                .hasMessage("Affected start and end date/time must be supplied together.");
    }

    @Test
    @DisplayName("VS13 (boundary): an affected end equal to the start is refused")
    void rejectsAnEndThatIsNotAfterTheStart() {
        when(venues.existsById(venueId)).thenReturn(true);
        var from = OffsetDateTime.parse("2027-03-10T12:00:00+08:00");

        assertThatThrownBy(() -> service.create(venueId, userId,
                new CreateVenueOperationalIssueRequest("Maintenance", from, from)))
                .isInstanceOf(InvalidVenueOperationalIssueException.class)
                .hasMessage("Affected end date/time must be after the start date/time.");
    }

    @Test
    @DisplayName("VS13 (negative): reporting an issue at a venue that does not exist is refused")
    void rejectsAnUnknownVenue() {
        when(venues.existsById(venueId)).thenReturn(false);

        assertThatThrownBy(() -> service.create(venueId, userId,
                new CreateVenueOperationalIssueRequest("Maintenance", null, null)))
                .isInstanceOf(com.example.connect_sphere.venue.service.VenueNotFoundException.class);
    }

    @Test
    @DisplayName("VS13 (happy): issues are listed for an existing venue")
    void listsIssuesForAnExistingVenue() {
        when(venues.existsById(venueId)).thenReturn(true);
        when(issues.findByVenueIdOrderByAffectedFromAscCreatedAtDesc(venueId)).thenReturn(List.of());

        assertThat(service.list(venueId)).isEmpty();
        verify(issues).findByVenueIdOrderByAffectedFromAscCreatedAtDesc(venueId);
    }

    @Test
    @DisplayName("VS13 (negative): listing the issues of a venue that does not exist is not found")
    void listingAnUnknownVenue() {
        when(venues.existsById(venueId)).thenReturn(false);

        assertThatThrownBy(() -> service.list(venueId))
                .isInstanceOf(com.example.connect_sphere.venue.service.VenueNotFoundException.class);
        org.mockito.Mockito.verifyNoInteractions(issues);
    }

    @Test
    @DisplayName("VS13 (boundary): a description must be given and at most 2000 characters; a longer one saves nothing")
    void descriptionLimits() {
        when(venues.existsById(venueId)).thenReturn(true);

        assertThatThrownBy(() -> service.create(venueId, userId, new CreateVenueOperationalIssueRequest(null, null, null)))
                .isInstanceOf(InvalidVenueOperationalIssueException.class).hasMessage("Issue description is required.");
        assertThatThrownBy(() -> service.create(venueId, userId,
                new CreateVenueOperationalIssueRequest("x".repeat(2001), null, null)))
                .isInstanceOf(InvalidVenueOperationalIssueException.class)
                .hasMessage("Issue description must be at most 2000 characters.");
        org.mockito.Mockito.verify(issues, org.mockito.Mockito.never()).saveAndFlush(any());

        assertThat(service.create(venueId, userId, new CreateVenueOperationalIssueRequest("x".repeat(2000), null, null))
                .description()).hasSize(2000);
    }

    @Test
    @DisplayName("VS13 VS17 (boundary): a timed issue affects only bookings whose occupied time overlaps it; ones that end before it or start after it are left alone")
    void onlyOverlappingBookingsAreAffected() {
        var availability = org.mockito.Mockito.mock(com.example.connect_sphere.venueavailability.repository.AvailabilityRepository.class);
        var publisher = org.mockito.Mockito.mock(org.springframework.context.ApplicationEventPublisher.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "availability", availability);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "publisher", publisher);
        when(venues.existsById(venueId)).thenReturn(true);
        var from = OffsetDateTime.parse("2027-03-10T13:00:00+08:00");
        var morning = new com.example.connect_sphere.venueavailability.dto.AvailabilityModels.Booking(UUID.randomUUID(),
                UUID.randomUUID(), null, "Morning talk", "approved", from.minusHours(4), from.minusHours(2),
                from.minusHours(5), from.minusHours(1));
        var afternoon = new com.example.connect_sphere.venueavailability.dto.AvailabilityModels.Booking(UUID.randomUUID(),
                UUID.randomUUID(), null, "Afternoon talk", "approved", from.plusHours(1), from.plusHours(2),
                from.plusMinutes(30), from.plusHours(3));
        var evening = new com.example.connect_sphere.venueavailability.dto.AvailabilityModels.Booking(UUID.randomUUID(),
                UUID.randomUUID(), null, "Evening talk", "approved", from.plusHours(5), from.plusHours(6),
                from.plusHours(4), from.plusHours(7));
        when(availability.bookings(venueId)).thenReturn(List.of(morning, afternoon, evening));

        service.create(venueId, userId, new CreateVenueOperationalIssueRequest("Power cut", from, from.plusHours(1)));

        verify(availability).notifyOperationalIssue(venueId, List.of(afternoon), "Power cut");
        verify(publisher).publishEvent(new com.example.connect_sphere.venueavailability.entity.VenueBecameUnavailable(
                List.of(afternoon.eventId()), userId, "A venue operational issue requires review: Power cut"));
    }

}
