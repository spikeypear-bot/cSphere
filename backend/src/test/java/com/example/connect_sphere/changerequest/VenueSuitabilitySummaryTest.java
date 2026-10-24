package com.example.connect_sphere.changerequest;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.*;
import com.example.connect_sphere.event.entity.Event;
import com.example.connect_sphere.venue.entity.Venue;
import com.example.connect_sphere.venue.repository.VenueRepository;
import com.example.connect_sphere.venueavailability.AvailabilityService;
import com.example.connect_sphere.venueavailability.AvailabilityModels.Booking;
import com.example.connect_sphere.venuebooking.entity.VenueBookingRecord;
import com.example.connect_sphere.venuebooking.repository.VenueBookingRecordRepository;
import com.example.connect_sphere.venuebooking.hold.VenueHoldRepository;
import com.example.connect_sphere.equipment.EquipmentReservationService;

@Tag("VS17") @Tag("unit")
class VenueSuitabilitySummaryTest {
    private final VenueRepository venues=mock(VenueRepository.class);
    private final AvailabilityService availability=mock(AvailabilityService.class);
    private final ChangeImpactService service=new ChangeImpactService(mock(VenueBookingRecordRepository.class),venues,
            availability,mock(VenueHoldRepository.class),mock(EquipmentReservationService.class));
    private final Event event=new Event();
    private final Venue venue=new Venue();
    private final VenueBookingRecord booking=new VenueBookingRecord();
    private final OffsetDateTime start=OffsetDateTime.parse("2027-03-10T09:00:00+08:00");
    @BeforeEach void setup() {
        event.setEventId(UUID.randomUUID()); event.setStartDatetime(start); event.setEndDatetime(start.plusHours(3));
        event.setExpectedAttendance(150); event.setVenueRequirements("Theatre seating");
        venue.setVenueId(UUID.randomUUID()); venue.setVenueCapacity(200); venue.setSupportedLayouts(List.of("theatre","classroom"));
        booking.setBookingId(UUID.randomUUID()); booking.setVenueId(venue.getVenueId()); booking.setEventId(event.getEventId());
        booking.setStartDatetime(start); booking.setEndDatetime(start.plusHours(3)); booking.setSetupDurationMinutes(30); booking.setTurnaroundDurationMinutes(45);
        when(venues.findById(venue.getVenueId())).thenReturn(Optional.of(venue));
        when(availability.conflictsForBooking(any(),any(),any(),any(),any())).thenReturn(List.of());
    }
    @Test @DisplayName("VS17 (happy): summary reports each successful check, buffered occupied times and actual layout information")
    void successfulSummary() {
        var summary=service.bookingSummary(event,booking,List.of());
        assertThat(summary.checks()).hasSize(8).allMatch(c -> c.status().equals("ok"));
        assertThat(summary.checks()).anyMatch(c -> c.title().equals("Capacity") && c.detail().equals("Fits 150 attendees (capacity 200)"));
        assertThat(summary.occupiedStart()).isEqualTo(OffsetDateTime.parse("2027-03-10T08:30:00+08:00"));
        assertThat(summary.occupiedEnd()).isEqualTo(OffsetDateTime.parse("2027-03-10T12:45:00+08:00"));
        assertThat(summary.venueRequirements()).isEqualTo("Theatre seating");
        assertThat(summary.supportedLayouts()).containsExactly("theatre","classroom");
        assertThat(booking.getStartDatetime()).isEqualTo(start);
    }
    @Test @DisplayName("VS17 (negative): missing structured requirements and closed hours are shown as specific problems")
    void unsuitable() {
        venue.setVenueCapacity(100); event.setAccessibilityNeeds(List.of("step_free_access")); event.setRequiredFacilities(List.of("stage"));
        var summary=service.bookingSummary(event,booking,List.of(new ChangeRequestDto.ImpactItem("venue:x:hours","venue","attention","Closed","Outside opening hours")));
        assertThat(summary.checks()).filteredOn(c -> c.status().equals("attention")).extracting(ChangeRequestDto.ImpactItem::key)
                .containsExactly("capacity","accessibility","facilities","hours");
        assertThat(summary.checks()).anyMatch(c -> c.detail().equals("150 attendees exceed capacity 100"));
    }
    @Test @DisplayName("VS17 (boundary): tentative hold conflicts are separated from confirmed booking conflicts")
    void holdsAndBookings() {
        var hold=new Booking(UUID.randomUUID(),event.getEventId(),null,"Other","hold",start,start.plusHours(1),start,start.plusHours(1));
        when(availability.conflictsForBooking(any(),any(),any(),any(),any())).thenReturn(List.of(hold));
        var summary=service.bookingSummary(event,booking,List.of());
        assertThat(summary.checks()).anyMatch(c -> c.key().equals("clash") && c.status().equals("ok"));
        assertThat(summary.checks()).anyMatch(c -> c.key().equals("hold") && c.status().equals("attention"));
        var other=new Booking(UUID.randomUUID(),event.getEventId(),null,"Other","approved",start,start.plusHours(1),start,start.plusHours(1));
        when(availability.conflictsForBooking(any(),any(),any(),any(),any())).thenReturn(List.of(other));
        var changed=service.bookingSummary(event,booking,List.of());
        assertThat(changed.checks()).anyMatch(c -> c.key().equals("clash") && c.status().equals("attention"));
        assertThat(changed.checks()).anyMatch(c -> c.key().equals("hold") && c.status().equals("ok"));
    }
}

