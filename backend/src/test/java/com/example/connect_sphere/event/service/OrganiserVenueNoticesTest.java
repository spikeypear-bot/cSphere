package com.example.connect_sphere.event.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

import com.example.connect_sphere.event.entity.Event;
import com.example.connect_sphere.event.entity.EventStatus;
import com.example.connect_sphere.event.repository.EventRepository;
import com.example.connect_sphere.notification.service.NotificationService;
import com.example.connect_sphere.user.entity.User;
import com.example.connect_sphere.user.entity.UserRole;
import com.example.connect_sphere.user.repository.UserRepository;
import com.example.connect_sphere.venue.entity.Venue;
import com.example.connect_sphere.venue.repository.VenueRepository;
import com.example.connect_sphere.venueavailability.VenueArrangementChanged;
import com.example.connect_sphere.venuebooking.entity.VenueBookingRecord;
import com.example.connect_sphere.venuebooking.entity.VenueBookingStatus;
import com.example.connect_sphere.venuebooking.repository.VenueBookingRecordRepository;

/**
 * EO-C1's rules on exact fixtures: who is told, when not, and what the notice
 * says. Repositories are stubs; NotificationService is a mock, since what
 * matters is whether and what is sent.
 */
@Tag("unit")
@Tag("EO-C1")
class OrganiserVenueNoticesTest {

    private final EventRepository events = mock(EventRepository.class);
    private final VenueBookingRecordRepository bookings = mock(VenueBookingRecordRepository.class);
    private final VenueRepository venues = mock(VenueRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final OrganiserVenueNotices notices = new OrganiserVenueNotices(events, bookings, venues, users, notifications);

    private final Event event = new Event();
    private final UUID organiser = UUID.randomUUID();
    private final List<VenueBookingRecord> eventBookings = new ArrayList<>();

    @BeforeEach
    void setUp() {
        event.setEventId(UUID.randomUUID());
        event.setEventName("Town Hall");
        event.setOrganisation("Acme");
        event.setStatus(EventStatus.confirmed);
        event.setFirstConfirmedAt(OffsetDateTime.now().minusDays(2));
        event.setEndDatetime(OffsetDateTime.now().plusDays(7));
        when(events.findById(event.getEventId())).thenReturn(Optional.of(event));
        User eo = new User();
        eo.setUserId(organiser);
        when(users.findByRoleAndOrganisation(UserRole.eo, "Acme")).thenReturn(List.of(eo));
        when(bookings.findForEvent(event.getEventId())).thenReturn(eventBookings);
    }

    private UUID booked(String address, VenueBookingStatus status) {
        Venue venue = new Venue();
        venue.setVenueId(UUID.randomUUID());
        venue.setVenueAddress(address);
        when(venues.findById(venue.getVenueId())).thenReturn(Optional.of(venue));
        VenueBookingRecord booking = new VenueBookingRecord();
        booking.setBookingId(UUID.randomUUID());
        booking.setVenueId(venue.getVenueId());
        booking.setEventId(event.getEventId());
        booking.setStatus(status);
        when(bookings.findById(booking.getBookingId())).thenReturn(Optional.of(booking));
        eventBookings.add(booking);
        return booking.getBookingId();
    }

    private String sent() {
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(notifications).createOrganiserVenueChangeNotifications(eq(List.of(organiser)), eq(event.getEventId()),
                any(), eq("Town Hall"), message.capture());
        return message.getValue();
    }

    @Test
    @DisplayName("EO-C1 AC1, AC6, AC7 (happy): the organiser of a confirmed event is told what changed, at which venue, why and when, and that it is being re-confirmed")
    void aConfirmedEventsOrganiserIsTold() {
        UUID hall = booked("1 Harbour Road", VenueBookingStatus.approved);

        notices.on(new VenueArrangementChanged(event.getEventId(), List.of(hall),
                "A venue for your event is unavailable from 10 Mar 2027, 8:00 AM to 10 Mar 2027, 1:00 PM (Singapore time).",
                "Flooding"));

        assertThat(sent()).contains("1 Harbour Road").contains("Reason: Flooding.").contains("(Singapore time)")
                .contains("being re-confirmed").contains("as not confirmed").doesNotContain("other venues");
    }

    @Test
    @DisplayName("EO-C1 AC2 (happy): an event back in Planning after being confirmed is still being re-confirmed, so its organiser is told")
    void anEventBeingReconfirmedStillCounts() {
        event.setStatus(EventStatus.pending);
        UUID hall = booked("1 Harbour Road", VenueBookingStatus.attention);

        notices.on(new VenueArrangementChanged(event.getEventId(), List.of(hall), "Times changed.", null));

        assertThat(sent()).contains("1 Harbour Road").doesNotContain("Reason:");
    }

    @Test
    @DisplayName("EO-C1 AC3 (negative): an event never confirmed is still being planned; venue changes stay internal")
    void neverConfirmedIsInternal() {
        event.setFirstConfirmedAt(null);
        event.setStatus(EventStatus.pending);

        notices.on(new VenueArrangementChanged(event.getEventId(), List.of(booked("Hall", VenueBookingStatus.approved)), "x", null));

        verifyNoInteractions(notifications);
    }

    @ParameterizedTest
    @EnumSource(value = EventStatus.class, names = {"cancelled", "completed"})
    @DisplayName("EO-C1 AC4 (negative): a cancelled or completed event's organiser is not told")
    void finishedEventsAreNotTold(EventStatus status) {
        event.setStatus(status);

        notices.on(new VenueArrangementChanged(event.getEventId(), List.of(booked("Hall", VenueBookingStatus.approved)), "x", null));

        verifyNoInteractions(notifications);
    }

    @Test
    @DisplayName("EO-C1 AC4 (boundary): an event that has ended is not told; one ending a minute from now still is")
    void anEndedEventIsNotTold() {
        UUID hall = booked("Hall", VenueBookingStatus.approved);
        event.setEndDatetime(OffsetDateTime.now().minusMinutes(1));
        notices.on(new VenueArrangementChanged(event.getEventId(), List.of(hall), "x", null));
        verifyNoInteractions(notifications);

        event.setEndDatetime(OffsetDateTime.now().plusMinutes(1));
        notices.on(new VenueArrangementChanged(event.getEventId(), List.of(hall), "x", null));
        verify(notifications).createOrganiserVenueChangeNotifications(anyList(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("EO-C1 AC11, AC12 (happy): with several venues, one notice names only the affected ones and says the others stay confirmed")
    void onlyTheAffectedVenuesAreNamed() {
        UUID main = booked("1 Harbour Road", VenueBookingStatus.approved);
        UUID breakout = booked("2 Marina Way", VenueBookingStatus.approved);
        booked("3 Bay Street", VenueBookingStatus.approved);

        notices.on(new VenueArrangementChanged(event.getEventId(), List.of(main, breakout), "An operational issue.", "Lift fault"));

        assertThat(sent()).contains("Affected venues: 1 Harbour Road; 2 Marina Way.").doesNotContain("3 Bay Street")
                .contains("Its other venues stay as confirmed.");
    }

    @Test
    @DisplayName("EO-C1 AC14 (happy): once confirmed again, the organiser hears the venues it now has, with their addresses")
    void theFollowUpNamesTheConfirmedVenues() {
        booked("2 Marina Way", VenueBookingStatus.approved);
        booked("9 Old Road", VenueBookingStatus.changed);

        notices.reconfirmed(event);

        assertThat(sent()).isEqualTo("Your event is confirmed again with this venue: 2 Marina Way. "
                + "You can share these arrangements with participants.");
    }

    @Test
    @DisplayName("EO-C1 AC13 (negative): with no approved venue, nothing is presented as confirmed")
    void noApprovedVenueNoFollowUp() {
        booked("Pending Hall", VenueBookingStatus.pending);

        notices.reconfirmed(event);

        verifyNoInteractions(notifications);
    }
}
