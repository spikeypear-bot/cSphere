package com.example.connect_sphere.changerequest;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import com.example.connect_sphere.event.entity.Event;
import com.example.connect_sphere.notification.service.NotificationService;
import com.example.connect_sphere.user.entity.*;
import com.example.connect_sphere.user.repository.UserRepository;
import com.example.connect_sphere.venue.entity.Venue;
import com.example.connect_sphere.venue.repository.VenueRepository;
import com.example.connect_sphere.venuebooking.entity.*;
import com.example.connect_sphere.venuebooking.repository.VenueBookingRecordRepository;

@Tag("VS17") @Tag("unit")
class VenueRequirementChangeTest {
    private final VenueRequirementReviewRepository reviews = mock(VenueRequirementReviewRepository.class);
    private final VenueBookingRecordRepository bookings = mock(VenueBookingRecordRepository.class);
    private final VenueRepository venues = mock(VenueRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final NotificationService notices = mock(NotificationService.class);
    private final VenueRequirementChangeService service = new VenueRequirementChangeService(reviews, bookings, venues, users, notices);
    private final OffsetDateTime start = OffsetDateTime.parse("2027-03-10T09:00:00+08:00");
    private Event event;
    private ChangeRequest change;

    @BeforeEach void setup() {
        event = new Event(); event.setEventId(UUID.randomUUID()); event.setEventName("Conference");
        event.setCoordinatorId(UUID.randomUUID());
        var coordinator = new User(); coordinator.setUsername("ec1"); coordinator.setEmail("ec1@example.test");
        when(users.findById(event.getCoordinatorId())).thenReturn(Optional.of(coordinator));
        change = new ChangeRequest(); change.setChangeRequestId(UUID.randomUUID());
        change.setStatus(ChangeRequestStatus.approved); change.setDecidedAt(start.minusDays(1));
        change.setDecidedBy(event.getCoordinatorId());
        change.setOriginalValues(new HashMap<>(Map.of("expectedAttendance", 100)));
        change.setProposedValues(new HashMap<>(Map.of("expectedAttendance", 200)));
        var staff = new User(); staff.setUserId(UUID.randomUUID()); staff.setRole(UserRole.vs);
        when(users.findByRole(UserRole.vs)).thenReturn(List.of(staff));
    }
    private VenueBookingRecord booking(VenueBookingStatus status) {
        var b = new VenueBookingRecord(); b.setBookingId(UUID.randomUUID()); b.setVenueId(UUID.randomUUID());
        b.setEventId(event.getEventId()); b.setStatus(status); b.setStartDatetime(start); b.setEndDatetime(start.plusHours(2));
        var venue = new Venue(); venue.setVenueAddress("Hall " + b.getVenueId());
        when(venues.findById(b.getVenueId())).thenReturn(Optional.of(venue)); return b;
    }
    @Test @DisplayName("VS17 (happy): pending and approved bookings receive separate historical reviews; terminal bookings do not")
    void activeBookingsAreIndependent() {
        var pending = booking(VenueBookingStatus.pending); var approved = booking(VenueBookingStatus.approved);
        var ownStart = start.plusHours(1); approved.setStartDatetime(ownStart);
        approved.setSetupDurationMinutes(30); approved.setTurnaroundDurationMinutes(45);
        var inactive = List.of(booking(VenueBookingStatus.changed), booking(VenueBookingStatus.cancelled), booking(VenueBookingStatus.rejected));
        when(bookings.findForEvent(event.getEventId())).thenReturn(List.of(pending, approved, inactive.get(0), inactive.get(1), inactive.get(2)));
        service.approved(change, event, start, start.plusHours(3));
        var captured = ArgumentCaptor.forClass(VenueRequirementReview.class);
        verify(reviews, times(2)).saveAndFlush(captured.capture());
        var first = captured.getAllValues().get(0); var second = captured.getAllValues().get(1);
        assertThat(first.getBookingId()).isEqualTo(pending.getBookingId());
        assertThat(second.getBookingId()).isEqualTo(approved.getBookingId());
        assertThat(second.getReservedStart()).isEqualTo(ownStart);
        assertThat(second.getSetupDurationMinutes()).isEqualTo(30);
        assertThat(second.getTurnaroundDurationMinutes()).isEqualTo(45);
        assertThat(first.getStatus()).isEqualTo("pending");
        assertThat(first.getApprovedBy()).isEqualTo(event.getCoordinatorId());
        assertThat(first.getCoordinatorEmail()).isEqualTo("ec1@example.test");
        change.getProposedValues().put("expectedAttendance", 999);
        assertThat(first.getPreviousRequirements()).containsEntry("expectedAttendance", 100);
        assertThat(first.getNewRequirements()).containsEntry("expectedAttendance", 200);
        verify(notices, times(2)).createVenueRequirementChangedNotifications(anyList(), any(), contains("100 → 200"));
        verify(bookings, never()).save(any());
        assertThat(approved.getStatus()).isEqualTo(VenueBookingStatus.approved);
    }
    @Test @DisplayName("VS17 (boundary): each supported venue field triggers review, with semantic equality ignored")
    void venueFields() {
        var b = booking(VenueBookingStatus.pending); when(bookings.findForEvent(event.getEventId())).thenReturn(List.of(b));
        for (String field : List.of("venueRequirements", "accessibilityNeeds", "requiredFacilities", "startDatetime", "endDatetime")) {
            change.setOriginalValues(Map.of(field, field.endsWith("Datetime") ? start.toString() : "before"));
            change.setProposedValues(Map.of(field, field.endsWith("Datetime") ? start.plusHours(1).toString() : "after"));
            service.approved(change, event, start, start.plusHours(3));
        }
        verify(reviews, times(5)).saveAndFlush(any());
        clearInvocations(reviews, notices);
        change.setOriginalValues(Map.of("startDatetime", start.toString(), "requiredFacilities", List.of("stage", "wifi")));
        change.setProposedValues(Map.of("startDatetime", start.toInstant().toString(), "requiredFacilities", List.of("wifi", "stage")));
        service.approved(change, event, start, start.plusHours(3));
        verify(reviews, never()).saveAndFlush(any()); verifyNoInteractions(notices);
    }
    @Test @DisplayName("VS17 (negative): pending, rejected, withdrawn and unrelated changes create no reviews or alerts")
    void onlyApprovedVenueChanges() {
        for (var state : List.of(ChangeRequestStatus.pending, ChangeRequestStatus.rejected, ChangeRequestStatus.withdrawn)) {
            change.setStatus(state); service.approved(change, event, start, start.plusHours(3));
        }
        change.setStatus(ChangeRequestStatus.approved);
        change.setOriginalValues(Map.of("purpose", "before", "equipmentRequirements", "before"));
        change.setProposedValues(Map.of("purpose", "after", "equipmentRequirements", "after"));
        service.approved(change, event, start, start.plusHours(3));
        service.approved(change, null, null, null);
        verifyNoInteractions(reviews, bookings, notices);
    }
    @Test @DisplayName("VS17 (conflict): repeated processing creates no duplicate review or notification")
    void duplicate() {
        var b = booking(VenueBookingStatus.approved); when(bookings.findForEvent(event.getEventId())).thenReturn(List.of(b));
        when(reviews.existsByChangeRequestIdAndBookingId(change.getChangeRequestId(), b.getBookingId())).thenReturn(true);
        service.approved(change, event, start, start.plusHours(3));
        verify(reviews, never()).saveAndFlush(any()); verifyNoInteractions(notices);
    }
    @Test @DisplayName("VS17 (boundary): legacy reservations retain the old event window after an approved date change")
    void legacy() {
        var b = booking(VenueBookingStatus.approved); b.setStartDatetime(null); b.setEndDatetime(null);
        when(bookings.findForEvent(event.getEventId())).thenReturn(List.of(b));
        change.setOriginalValues(Map.of("startDatetime", start.toString()));
        change.setProposedValues(Map.of("startDatetime", start.plusDays(1).toString()));
        service.approved(change, event, start, start.plusHours(3));
        assertThat(b.getStartDatetime()).isEqualTo(start); assertThat(b.getEndDatetime()).isEqualTo(start.plusHours(3));
        verify(bookings).save(b); assertThat(b.getStatus()).isEqualTo(VenueBookingStatus.approved);
    }
    @Test @DisplayName("VS17 (failure): a notification persistence failure propagates to roll back approval")
    void notificationFailureIsNotSwallowed() {
        var pending = booking(VenueBookingStatus.pending);
        when(bookings.findForEvent(event.getEventId())).thenReturn(List.of(pending));
        doThrow(new IllegalStateException("Storage unavailable")).when(notices).createVenueRequirementChangedNotifications(anyList(), any(), anyString());
        assertThatThrownBy(() -> service.approved(change, event, start, start.plusHours(3))).hasMessage("Storage unavailable");
    }
}
