package com.example.connect_sphere.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.example.connect_sphere.notification.dto.NotificationDto;
import com.example.connect_sphere.notification.entity.Notification;
import com.example.connect_sphere.notification.entity.NotificationType;
import com.example.connect_sphere.notification.repository.NotificationRepository;

/**
 * The D21/V14 notification types: each is sent to one role, carries what the
 * recipient needs, and links into that role's own console. Links are
 * precomputed server-side, so a wrong one would silently send people to a
 * page they cannot open.
 */
class NotificationLinkTest {

    @Mock private NotificationRepository repository;
    private NotificationService service;

    private static final UUID ORGANISER = UUID.randomUUID();
    private static final UUID SECOND_ORGANISER = UUID.randomUUID();
    private static final UUID REQUEST = UUID.randomUUID();
    private static final UUID EVENT = UUID.randomUUID();
    private static final UUID BOOKING = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new NotificationService(repository);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    /** What the recipient would see for the notifications just created. */
    private List<NotificationDto> delivered() {
        ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
        verify(repository, atLeastOnce()).save(saved.capture());
        when(repository.findByRecipientUserIdOrderByOccurredAtDesc(any())).thenReturn(saved.getAllValues());
        return service.list(UUID.randomUUID());
    }

    @Test
    void aClarificationRequestReachesEveryOrganiserAndOpensTheirRespondPage() {
        service.createClarificationRequestedNotifications(
                List.of(ORGANISER, SECOND_ORGANISER), REQUEST, "Town Hall", "Is 150 final?");

        List<NotificationDto> sent = delivered();
        assertThat(sent).hasSize(2);
        assertThat(sent).allSatisfy(n -> {
            assertThat(n.type()).isEqualTo(NotificationType.clarification_requested.name());
            assertThat(n.message()).isEqualTo("Is 150 final?");
            assertThat(n.linkPath()).isEqualTo("/organiser/requests/" + REQUEST + "/respond");
        });
    }

    @Test
    void aResponseOpensTheCoordinatorsReviewScreen() {
        service.createClarificationRespondedNotification(UUID.randomUUID(), REQUEST, "Town Hall", "120 confirmed");

        NotificationDto sent = delivered().get(0);
        assertThat(sent.message()).isEqualTo("120 confirmed");
        assertThat(sent.linkPath()).isEqualTo("/coordinator/requests/" + REQUEST);
    }

    @Test
    void bookingRequestedAndCancelledBothOpenTheBookingForVenueStaff() {
        service.createVenueBookingRequestedNotifications(List.of(UUID.randomUUID()), BOOKING, EVENT, "Town Hall");
        service.createVenueBookingCancelledNotifications(List.of(UUID.randomUUID()), BOOKING, EVENT, "Town Hall",
                "Wrong venue");

        List<NotificationDto> sent = delivered();
        assertThat(sent).extracting(NotificationDto::linkPath)
                .containsOnly("/venue-staff/bookings/" + BOOKING);
        assertThat(sent).filteredOn(n -> n.type().equals("venue_booking_cancelled"))
                .extracting(NotificationDto::reason).containsExactly("Wrong venue");
    }

    @Test
    void theOriginalStatusAndAssignmentLinksAreUnchanged() {
        service.createStatusChangeNotification(ORGANISER, REQUEST, EVENT, "Town Hall", "approved", null);
        service.createStatusChangeNotification(ORGANISER, REQUEST, null, "Town Hall", "rejected", "No venue");

        assertThat(delivered()).extracting(NotificationDto::linkPath).containsExactly(
                "/organiser/events/" + EVENT, "/organiser/requests/" + REQUEST);
    }
}
