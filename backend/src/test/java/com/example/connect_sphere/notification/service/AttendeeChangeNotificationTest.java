package com.example.connect_sphere.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.example.connect_sphere.attendee.entity.AttendeeRegistration;
import com.example.connect_sphere.attendee.repository.AttendeeRegistrationRepository;
import com.example.connect_sphere.notification.entity.Notification;
import com.example.connect_sphere.notification.entity.NotificationChange;
import com.example.connect_sphere.notification.entity.NotificationType;
import com.example.connect_sphere.notification.repository.NotificationRepository;

/** AT09's venue-change notice, built once per confirmed attendee; the registrations are a stub. */
@Tag("unit")
@Tag("AT09")
class AttendeeChangeNotificationTest {
    private static final UUID EVENT = UUID.randomUUID();
    private static final OffsetDateTime CONFIRMED = OffsetDateTime.parse("2027-03-01T02:00:00Z");

    private final NotificationRepository repository = mock(NotificationRepository.class);
    private final AttendeeRegistrationRepository registrations = mock(AttendeeRegistrationRepository.class);
    private final NotificationService notifications = new NotificationService(repository, Clock.systemUTC());
    private final AttendeeRegistration attendee = registration();

    {
        ReflectionTestUtils.setField(notifications, "attendeeRegistrations", registrations);
        when(registrations.findByEventIdAndStatusAndAttendeeIdIsNotNull(EVENT, "confirmed")).thenReturn(List.of(attendee));
    }

    private static AttendeeRegistration registration() {
        AttendeeRegistration r = new AttendeeRegistration();
        r.setRegistrationId(UUID.randomUUID());
        r.setAttendeeId(UUID.randomUUID());
        return r;
    }

    private List<Notification> saved(int times) {
        ArgumentCaptor<Notification> all = ArgumentCaptor.forClass(Notification.class);
        verify(repository, times(times)).save(all.capture());
        return all.getAllValues();
    }

    @Test
    @DisplayName("AT09 AC2 (happy): a reconfirmation with different venues tells each confirmed attendee the old and new venues, opening the event")
    void aVenueChangeNamesBothVenues() {
        notifications.createAttendeeVenueChangeNotifications(EVENT, "Town Hall", "Harbour Hall", "Marina Hall", CONFIRMED);

        Notification notice = saved(1).getFirst();
        assertThat(notice.getRecipientUserId()).isEqualTo(attendee.getAttendeeId());
        assertThat(notice.getRegistrationId()).isEqualTo(attendee.getRegistrationId());
        assertThat(notice.getType()).isEqualTo(NotificationType.attendee_event_changed);
        assertThat(notice.getEventId()).isEqualTo(EVENT);
        assertThat(notice.getOccurredAt()).isEqualTo(CONFIRMED);
        assertThat(notice.getMessage()).isEqualTo("Important event arrangements have changed.");
        assertThat(notice.getNotificationChanges())
                .containsExactly(new NotificationChange("venue", "Venue", "Harbour Hall", "Marina Hall"));
        when(repository.findByRecipientUserIdOrderByOccurredAtDesc(attendee.getAttendeeId())).thenReturn(List.of(notice));
        assertThat(notifications.list(attendee.getAttendeeId()).getFirst().linkPath()).isEqualTo("/attendee/events/" + EVENT);
    }

    @Test
    @DisplayName("AT09 AC20, AC21 (conflict): a retry of the same confirmation is the same notice; a later confirmation is a new one")
    void sameConfirmationSameNotice() {
        notifications.createAttendeeVenueChangeNotifications(EVENT, "Town Hall", "Harbour Hall", "Marina Hall", CONFIRMED);
        notifications.createAttendeeVenueChangeNotifications(EVENT, "Town Hall", "Harbour Hall", "Marina Hall", CONFIRMED);
        notifications.createAttendeeVenueChangeNotifications(EVENT, "Town Hall", "Marina Hall", "Harbour Hall",
                CONFIRMED.plusDays(1));

        List<Notification> notices = saved(3);
        assertThat(notices.get(0).getNotificationId()).isEqualTo(notices.get(1).getNotificationId());
        assertThat(notices.get(2).getNotificationId()).isNotEqualTo(notices.get(0).getNotificationId());
    }

    @Test
    @DisplayName("AT09 (negative): an approved change with nothing attendees need to know tells no one")
    void noChangesNoNotice() {
        notifications.createAttendeeEventChangeNotifications(UUID.randomUUID(), EVENT, "Town Hall", List.of(), CONFIRMED);

        verify(repository, never()).save(any());
        verifyNoInteractions(registrations);
    }
}
