package com.example.connect_sphere.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;

import com.example.connect_sphere.notification.dto.NotificationDto;
import com.example.connect_sphere.notification.entity.Notification;
import com.example.connect_sphere.notification.entity.NotificationType;
import com.example.connect_sphere.notification.repository.NotificationRepository;
import com.example.connect_sphere.user.entity.User;
import com.example.connect_sphere.user.repository.UserRepository;

/**
 * EC-NEW4's rules that apply to every notification, checked once at the
 * single point they are enforced: who did it is recorded, nobody is told about
 * their own action, "mark all as read" stops at what was seen, and each new
 * kind opens where its reader can act. The repository is a stub; the signed-in
 * user is a JWT in the security context, as in a real request.
 */
@Tag("unit")
@Tag("EC-NEW4")
@Tag("AT03")
class CoordinatorNotificationsTest {

    private static final UUID LEAD = UUID.randomUUID();
    private static final UUID COORDINATOR = UUID.randomUUID();
    private static final UUID REQUEST = UUID.randomUUID();
    private static final UUID EVENT = UUID.randomUUID();

    private final NotificationRepository repository = mock(NotificationRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final NotificationService service = new NotificationService(repository, Clock.systemUTC());

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "users", users);
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        User lead = new User();
        lead.setUserId(LEAD);
        lead.setUsername("ecl1");
        when(users.findById(LEAD)).thenReturn(Optional.of(lead));
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    private static void signedInAs(UUID user, String role) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject(user.toString()).claim("role", role).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    private Notification saved() {
        ArgumentCaptor<Notification> n = ArgumentCaptor.forClass(Notification.class);
        verify(repository).save(n.capture());
        return n.getValue();
    }

    private NotificationDto asRead(Notification n) {
        when(repository.findByRecipientUserIdOrderByOccurredAtDesc(n.getRecipientUserId())).thenReturn(List.of(n));
        return service.list(n.getRecipientUserId()).get(0);
    }

    @Test
    @DisplayName("EC-NEW4 AC17 (happy): each notification records who did it and in which role")
    void whoDidItIsRecorded() {
        signedInAs(LEAD, "ecl");

        service.createRequestAssignedNotification(COORDINATOR, REQUEST, null, "Town Hall", false, "Assigned to you: Acme");

        NotificationDto dto = asRead(saved());
        assertThat(dto.actorName()).isEqualTo("ecl1");
        assertThat(dto.actorRole()).isEqualTo("ecl");
        assertThat(dto.message()).isEqualTo("Assigned to you: Acme");
    }

    @Test
    @DisplayName("EC-NEW4 AC17 (boundary): what the system does on its own (no signed-in user) records no actor")
    void noActorForTheSystem() {
        service.createRequestAssignedNotification(COORDINATOR, REQUEST, null, "Town Hall", false, "Assigned to you");

        assertThat(asRead(saved()).actorName()).isNull();
    }

    @Test
    @DisplayName("EC-NEW4 AC15 (negative): nobody is notified about their own action")
    void notAboutYourOwnAction() {
        signedInAs(COORDINATOR, "ec");

        service.createReturnedToPlanningNotification(COORDINATOR, EVENT, "Town Hall", "An approved change");
        service.createRequestAssignedNotification(COORDINATOR, REQUEST, null, "Town Hall", false, "Assigned to you");

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("EC-NEW4 AC15 (boundary): an attendee's own registration confirmation is the one notice about one's own action")
    void anAttendeesOwnConfirmationStillArrives() {
        UUID attendee = UUID.randomUUID();
        UUID registration = UUID.randomUUID();
        signedInAs(attendee, "attendee");

        service.createAttendeeRegistrationConfirmation(attendee, EVENT, "Town Hall", "Registered: ref ABC123", registration);

        assertThat(saved().getRecipientUserId()).isEqualTo(attendee);
        assertThat(saved().getRegistrationId()).isEqualTo(registration);
    }

    @Test
    @DisplayName("EC-NEW4 AC25, AC26 (boundary): mark all as read stops at the newest notice seen; a future or missing bound means now")
    void markAllReadStopsAtWhatWasSeen() {
        OffsetDateTime seen = OffsetDateTime.parse("2026-10-28T02:00:00Z");
        when(repository.markAllRead(eq(COORDINATOR), any(), any())).thenReturn(3);

        assertThat(service.markAllRead(COORDINATOR, seen)).isEqualTo(3);
        verify(repository).markAllRead(eq(COORDINATOR), eq(seen), any());

        OffsetDateTime before = OffsetDateTime.now();
        service.markAllRead(COORDINATOR, OffsetDateTime.now().plusDays(1));
        ArgumentCaptor<OffsetDateTime> upTo = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(repository, org.mockito.Mockito.times(2)).markAllRead(eq(COORDINATOR), upTo.capture(), any());
        assertThat(upTo.getAllValues().get(1)).isBetween(before, OffsetDateTime.now());
    }

    @Test
    @DisplayName("EC-NEW4 AC1 (negative): another person's notification cannot be marked read, and is reported as not found")
    void notAnotherPersonsNotification() {
        Notification theirs = new Notification();
        theirs.setNotificationId(UUID.randomUUID());
        theirs.setRecipientUserId(LEAD);
        when(repository.findById(theirs.getNotificationId())).thenReturn(Optional.of(theirs));

        assertThatThrownBy(() -> service.markRead(COORDINATOR, theirs.getNotificationId()))
                .isInstanceOf(NotificationNotFoundException.class);
        assertThat(theirs.getReadAt()).isNull();
    }

    @Test
    @DisplayName("EC-NEW4 AC19, AC21 (happy): each new kind opens where its reader acts; a handed-over event opens the event, which then says it is not theirs")
    void eachKindOpensWhereItsReaderActs() {
        service.createRequestSubmittedNotifications(List.of(LEAD), REQUEST, "Town Hall", "Waiting to be assigned");
        service.createRequestAssignedNotification(COORDINATOR, REQUEST, null, "Town Hall", false, "Assigned to you");
        service.createRequestHandedOverNotification(COORDINATOR, REQUEST, EVENT, "Town Hall", "Reassigned to ec2.");
        service.createEquipmentRequestDecidedNotification(COORDINATOR, EVENT, "Town Hall", "No mics", "Rejected: No mics");
        ArgumentCaptor<Notification> all = ArgumentCaptor.forClass(Notification.class);
        verify(repository, org.mockito.Mockito.times(4)).save(all.capture());

        assertThat(all.getAllValues().stream().map(this::asRead).map(NotificationDto::linkPath)).containsExactly(
                "/coordinator-lead/unassigned-requests/" + REQUEST,
                "/coordinator/requests/" + REQUEST,
                "/coordinator/events/" + EVENT,
                "/coordinator/events/" + EVENT + "/equipment-request");
        assertThat(all.getAllValues()).extracting(Notification::getType).containsExactly(
                NotificationType.request_submitted, NotificationType.request_assigned,
                NotificationType.request_handed_over, NotificationType.equipment_request_decided);
    }

    @Test
    @DisplayName("EC-NEW4 AC9 (happy): a rejected booking names the reason and any suggested alternative; an approval names no internal ids")
    void bookingDecisionsReadAsPeopleSayThem() {
        service.createVenueBookingRejectedNotification(COORDINATOR, UUID.randomUUID(), EVENT, "Town Hall", "Harbour Hall",
                "Closed for repairs", "Suggested instead: Marina Hall");
        service.createVenueBookingApprovedNotification(COORDINATOR, UUID.randomUUID(), EVENT, "Town Hall", "Marina Hall");
        ArgumentCaptor<Notification> all = ArgumentCaptor.forClass(Notification.class);
        verify(repository, org.mockito.Mockito.times(2)).save(all.capture());

        assertThat(all.getAllValues().get(0).getMessage())
                .isEqualTo("Booking at Harbour Hall was rejected: Closed for repairs. Suggested instead: Marina Hall");
        assertThat(all.getAllValues().get(1).getMessage()).isEqualTo("Booking at Marina Hall was approved.");
    }

    @Test
    @DisplayName("EC-NEW4 AC9 (boundary): a rejection with no suggested alternative, missing or blank, ends at the reason")
    void aRejectionWithoutAnAlternativeEndsAtTheReason() {
        service.createVenueBookingRejectedNotification(COORDINATOR, UUID.randomUUID(), EVENT, "Town Hall", "Harbour Hall",
                "Closed for repairs", null);
        service.createVenueBookingRejectedNotification(COORDINATOR, UUID.randomUUID(), EVENT, "Town Hall", "Harbour Hall",
                "Closed for repairs", "  ");
        ArgumentCaptor<Notification> all = ArgumentCaptor.forClass(Notification.class);
        verify(repository, org.mockito.Mockito.times(2)).save(all.capture());

        assertThat(all.getAllValues()).extracting(Notification::getMessage)
                .containsOnly("Booking at Harbour Hall was rejected: Closed for repairs");
    }

    @Test
    @DisplayName("EC-NEW4 AC25 (boundary): mark all as read with no bound marks everything up to now")
    void markAllReadWithNoBoundMeansNow() {
        OffsetDateTime before = OffsetDateTime.now();

        service.markAllRead(COORDINATOR, null);

        ArgumentCaptor<OffsetDateTime> upTo = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(repository).markAllRead(eq(COORDINATOR), upTo.capture(), any());
        assertThat(upTo.getValue()).isBetween(before, OffsetDateTime.now());
    }

    @Test
    @DisplayName("EC-NEW4 AC17 (boundary): a signed-in token that names no user records no actor, and the notice still arrives")
    void aTokenWithoutAUserIdRecordsNoActor() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("scheduler").claim("role", "ecl").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));

        service.createRequestAssignedNotification(COORDINATOR, REQUEST, null, "Town Hall", false, "Assigned to you");

        Notification n = saved();
        assertThat(n.getRecipientUserId()).isEqualTo(COORDINATOR);
        assertThat(n.getActorUserId()).isNull();
        assertThat(n.getActorName()).isNull();
    }

    @Test
    @DisplayName("EC-NEW4 (boundary): an event with no coordinator yet sends no coordinator notice")
    void noCoordinatorNoNotice() {
        var review = new com.example.connect_sphere.changerequest.entity.VenueRequirementReview();
        service.createEquipmentRequestDecidedNotification(null, EVENT, "Town Hall", "No mics", "Rejected: No mics");
        service.createVenueBookingRejectedNotification(null, UUID.randomUUID(), EVENT, "Town Hall", "Harbour Hall", "Closed", null);
        service.createVenueBookingApprovedNotification(null, UUID.randomUUID(), EVENT, "Town Hall", "Harbour Hall");
        service.createHoldExpiredNotification(null, EVENT, UUID.randomUUID(), "Town Hall", OffsetDateTime.now());
        service.createVenueRequirementReviewedNotification(null, review, "Reviewed");

        verify(repository, never()).save(any());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("EC-NEW4 AC19 (happy): every Lead is told of a reassignment request, which opens the Lead's queue; the decision opens the coordinator's reassignment page")
    void reassignmentNoticesOpenWhereEachReaderActs() {
        UUID otherLead = UUID.randomUUID();
        service.createEventReassignmentRequestedNotifications(List.of(LEAD, otherLead), EVENT, "Town Hall",
                "I am no longer available.");
        service.createEventReassignmentDecisionNotifications(List.of(COORDINATOR), EVENT, "Town Hall",
                NotificationType.event_reassignment_approved, "Reassigned to ec2.");
        service.createEventReassignmentDecisionNotifications(List.of(COORDINATOR), EVENT, "Town Hall",
                NotificationType.event_reassignment_declined, "Please keep the event.");
        ArgumentCaptor<Notification> all = ArgumentCaptor.forClass(Notification.class);
        verify(repository, org.mockito.Mockito.times(4)).save(all.capture());

        assertThat(all.getAllValues()).extracting(Notification::getRecipientUserId)
                .containsExactly(LEAD, otherLead, COORDINATOR, COORDINATOR);
        assertThat(all.getAllValues()).extracting(Notification::getType).containsExactly(
                NotificationType.event_reassignment_requested, NotificationType.event_reassignment_requested,
                NotificationType.event_reassignment_approved, NotificationType.event_reassignment_declined);
        assertThat(all.getAllValues()).extracting(Notification::getMessage).containsExactly(
                "I am no longer available.", "I am no longer available.", "Reassigned to ec2.", "Please keep the event.");
        assertThat(all.getAllValues().stream().map(this::asRead).map(NotificationDto::linkPath)).containsExactly(
                "/coordinator-lead/reassignment-requests", "/coordinator-lead/reassignment-requests",
                "/coordinator/events/" + EVENT + "/reassignment-request",
                "/coordinator/events/" + EVENT + "/reassignment-request");
    }
}
