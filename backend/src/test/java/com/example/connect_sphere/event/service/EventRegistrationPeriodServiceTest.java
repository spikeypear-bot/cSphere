package com.example.connect_sphere.event.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.example.connect_sphere.activity.entity.ActivityType;
import com.example.connect_sphere.activity.service.ActivityService;
import com.example.connect_sphere.event.dto.RegistrationInfoDto;
import com.example.connect_sphere.event.dto.RegistrationPeriodRequest;
import com.example.connect_sphere.event.entity.Event;
import com.example.connect_sphere.event.entity.EventStatus;
import com.example.connect_sphere.event.entity.EventRegistration;
import com.example.connect_sphere.event.repository.EventRegistrationRepository;
import com.example.connect_sphere.event.repository.EventRepository;
import com.example.connect_sphere.eventrequest.entity.EventRequest;
import com.example.connect_sphere.eventrequest.repository.EventRequestRepository;

@Tag("unit")
@Tag("EO12")
class EventRegistrationPeriodServiceTest {
    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final UUID ORGANISER_ID = UUID.randomUUID();
    private static final UUID REQUEST_ID = UUID.randomUUID();
    private static final String ORGANISATION = "Acme";
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2030-01-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC);

    private final EventRepository events = org.mockito.Mockito.mock(EventRepository.class);
    private final EventRegistrationRepository registrations = org.mockito.Mockito.mock(EventRegistrationRepository.class);
    private final EventRequestRepository requests = org.mockito.Mockito.mock(EventRequestRepository.class);
    private final ActivityService activity = org.mockito.Mockito.mock(ActivityService.class);
    private final EventRegistrationService registrationInfo =
            new EventRegistrationService(events, registrations, CLOCK);
    private final EventRegistrationPeriodService service =
            new EventRegistrationPeriodService(events, requests, activity, registrationInfo, CLOCK);

    private Event event;

    @BeforeEach
    void setUp() {
        event = new Event();
        event.setEventId(EVENT_ID);
        event.setEventName("Fair");
        event.setOrganisation(ORGANISATION);
        event.setRegistrationNeeds(true);
        event.setRegistrationCapacity(5);
        event.setStartDatetime(NOW.plusDays(5));
        event.setEndDatetime(NOW.plusDays(6));
        event.setStatus(EventStatus.confirmed);

        EventRequest origin = new EventRequest();
        origin.setRequestId(REQUEST_ID);
        when(events.findForUpdate(EVENT_ID)).thenReturn(Optional.of(event));
        when(events.findById(EVENT_ID)).thenReturn(Optional.of(event));
        when(registrations.findByEventIdOrderByRegisteredAtAsc(EVENT_ID)).thenReturn(java.util.List.of());
        when(requests.findByEventId(EVENT_ID)).thenReturn(Optional.of(origin));
    }

    @Test
    @DisplayName("EO12 AC1 (negative): another organisation cannot update the event and no values are written")
    void refusesAnotherOrganisationWithoutChangingThePeriod() {
        assertThatThrownBy(() -> service.update("Other", ORGANISER_ID, EVENT_ID, validRequest()))
                .isInstanceOf(EventNotFoundException.class);
        assertThat(event.getRegistrationOpensAt()).isNull();
        assertThat(event.getRegistrationClosesAt()).isNull();
        verify(events, never()).save(any());
        verify(activity, never()).record(any());
    }

    @Test
    @DisplayName("EO12 AC3 (boundary): closing at or before opening is rejected without changing the saved period")
    void refusesEqualOrEarlierClose() {
        OffsetDateTime opening = NOW.plusDays(1);
        for (OffsetDateTime closing : new OffsetDateTime[] { opening, opening.minusNanos(1) }) {
            assertThatThrownBy(() -> service.update(ORGANISATION, ORGANISER_ID, EVENT_ID,
                    new RegistrationPeriodRequest(opening.toString(), closing.toString())))
                    .isInstanceOf(RegistrationPeriodException.class)
                    .hasMessageContaining("must be after");
            assertThat(event.getRegistrationOpensAt()).isNull();
            assertThat(event.getRegistrationClosesAt()).isNull();
        }
        verify(events, never()).save(any());
        verify(activity, never()).record(any());
    }

    @Test
    @DisplayName("EO12 AC4 (negative): missing time and an impossible calendar date are rejected")
    void refusesIncompleteAndImpossibleDateTimes() {
        assertThatThrownBy(() -> service.update(ORGANISATION, ORGANISER_ID, EVENT_ID,
                new RegistrationPeriodRequest("2030-01-02", "2030-01-03T00:00:00Z")))
                .isInstanceOf(RegistrationPeriodException.class)
                .hasMessageContaining("valid registration opening");
        assertThatThrownBy(() -> service.update(ORGANISATION, ORGANISER_ID, EVENT_ID,
                new RegistrationPeriodRequest("2030-02-31T10:00:00+08:00", "2030-03-01T10:00:00+08:00")))
                .isInstanceOf(RegistrationPeriodException.class)
                .hasMessageContaining("valid registration opening");
        assertThat(event.getRegistrationOpensAt()).isNull();
        assertThat(event.getRegistrationClosesAt()).isNull();
        verify(events, never()).save(any());
        verify(activity, never()).record(any());
    }

    @Test
    @DisplayName("EO12 AC5 (negative): closing later than the event start is rejected")
    void refusesCloseAfterEventStart() {
        assertThatThrownBy(() -> service.update(ORGANISATION, ORGANISER_ID, EVENT_ID,
                new RegistrationPeriodRequest(NOW.plusDays(1).toString(), NOW.plusDays(5).plusNanos(1).toString())))
                .isInstanceOf(RegistrationPeriodException.class)
                .hasMessageContaining("no later than the event start");
        assertThat(event.getRegistrationOpensAt()).isNull();
        assertThat(event.getRegistrationClosesAt()).isNull();
        verify(events, never()).save(any());
    }

    @Test
    @DisplayName("EO12 AC6 (negative): a first opening time in the past is rejected")
    void refusesPastOpeningOnFirstConfiguration() {
        assertThatThrownBy(() -> service.update(ORGANISATION, ORGANISER_ID, EVENT_ID,
                new RegistrationPeriodRequest(NOW.minusSeconds(1).toString(), NOW.plusDays(2).toString())))
                .isInstanceOf(RegistrationPeriodException.class)
                .hasMessageContaining("cannot be in the past");
        assertThat(event.getRegistrationOpensAt()).isNull();
        assertThat(event.getRegistrationClosesAt()).isNull();
        verify(events, never()).save(any());
    }

    @Test
    @DisplayName("EO12 AC6 (happy): once registration has opened only the closing time can change")
    void keepsOpenedTimeFixedAndAllowsCloseChange() {
        OffsetDateTime opened = NOW.minusHours(1);
        event.setRegistrationOpensAt(opened);
        event.setRegistrationClosesAt(NOW.plusDays(2));
        OffsetDateTime updatedClose = NOW.plusDays(3);

        service.update(ORGANISATION, ORGANISER_ID, EVENT_ID,
                new RegistrationPeriodRequest(opened.toString(), updatedClose.toString()));

        assertThat(event.getRegistrationOpensAt()).isEqualTo(opened);
        assertThat(event.getRegistrationClosesAt()).isEqualTo(updatedClose);
        verify(events).save(event);
        verify(activity).record(any());
    }

    @Test
    @DisplayName("EO12 AC6 (negative): changing the opening time after it has opened is rejected")
    void refusesChangingAnOpenedTime() {
        OffsetDateTime opened = NOW.minusHours(1);
        event.setRegistrationOpensAt(opened);
        event.setRegistrationClosesAt(NOW.plusDays(2));

        assertThatThrownBy(() -> service.update(ORGANISATION, ORGANISER_ID, EVENT_ID,
                new RegistrationPeriodRequest(NOW.plusHours(1).toString(), NOW.plusDays(3).toString())))
                .isInstanceOf(RegistrationPeriodException.class)
                .hasMessageContaining("only the closing");
        assertThat(event.getRegistrationOpensAt()).isEqualTo(opened);
        assertThat(event.getRegistrationClosesAt()).isEqualTo(NOW.plusDays(2));
        verify(events, never()).save(any());
    }

    @Test
    @DisplayName("EO12 AC12 (negative): an event without registration cannot be configured")
    void refusesWhenRegistrationIsNotRequired() {
        event.setRegistrationNeeds(false);

        assertThatThrownBy(() -> service.update(ORGANISATION, ORGANISER_ID, EVENT_ID, validRequest()))
                .isInstanceOf(RegistrationPeriodException.class)
                .hasMessageContaining("Registration is not required");
        verify(events, never()).save(any());
        verify(activity, never()).record(any());
    }

    @Test
    @DisplayName("EO12 AC13 (negative): cancelled, completed and ended events cannot be changed")
    void refusesCancelledCompletedAndEndedEvents() {
        event.setStatus(EventStatus.cancelled);
        assertThatThrownBy(() -> service.update(ORGANISATION, ORGANISER_ID, EVENT_ID, validRequest()))
                .isInstanceOf(RegistrationPeriodException.class)
                .hasMessageContaining("cancelled or ended");

        event.setStatus(EventStatus.completed);
        assertThatThrownBy(() -> service.update(ORGANISATION, ORGANISER_ID, EVENT_ID, validRequest()))
                .isInstanceOf(RegistrationPeriodException.class)
                .hasMessageContaining("cancelled or ended");

        event.setStatus(EventStatus.confirmed);
        event.setEndDatetime(NOW);
        assertThatThrownBy(() -> service.update(ORGANISATION, ORGANISER_ID, EVENT_ID, validRequest()))
                .isInstanceOf(RegistrationPeriodException.class)
                .hasMessageContaining("cancelled or ended");
        verify(events, never()).save(any());
        verify(activity, never()).record(any());
    }

    @Test
    @DisplayName("EO12 AC14, AC16 (happy): save returns the refreshed state and audits the organiser's new period")
    void savesPeriodReturnsStatusAndRecordsOwner() {
        RegistrationInfoDto result = service.update(ORGANISATION, ORGANISER_ID, EVENT_ID, validRequest());
        ArgumentCaptor<ActivityService.Entry> entry = ArgumentCaptor.forClass(ActivityService.Entry.class);

        assertThat(result.registrationState()).isEqualTo("not_yet_open");
        assertThat(event.getRegistrationOpensAt()).isEqualTo(NOW.plusDays(1));
        assertThat(event.getRegistrationClosesAt()).isEqualTo(NOW.plusDays(4));
        verify(events).save(event);
        verify(activity).record(entry.capture());
        assertThat(entry.getValue().type()).isEqualTo(ActivityType.registration_period_updated);
        assertThat(entry.getValue().actorUserId()).isEqualTo(ORGANISER_ID);
        assertThat(entry.getValue().requestId()).isEqualTo(REQUEST_ID);
        assertThat(entry.getValue().fieldValues())
                .containsEntry("registrationOpensAt", NOW.plusDays(1).toString())
                .containsEntry("registrationClosesAt", NOW.plusDays(4).toString());
    }

    @Test
    @DisplayName("EO12 AC11, AC14 (boundary): extending a close reopens registration only when places remain")
    void extendedCloseUsesCurrentCapacityForUpdatedStatus() {
        OffsetDateTime opened = NOW.minusHours(1);
        event.setRegistrationOpensAt(opened);
        event.setRegistrationClosesAt(NOW.minusSeconds(1));

        RegistrationInfoDto reopened = service.update(ORGANISATION, ORGANISER_ID, EVENT_ID,
                new RegistrationPeriodRequest(opened.toString(), NOW.plusDays(2).toString()));
        assertThat(reopened.registrationState()).isEqualTo("open");
        org.mockito.Mockito.verify(registrations, never()).save(any());

        EventRegistration confirmed = new EventRegistration();
        confirmed.setStatus("confirmed");
        when(registrations.findByEventIdOrderByRegisteredAtAsc(EVENT_ID))
                .thenReturn(java.util.List.of(confirmed, confirmed, confirmed, confirmed, confirmed));
        RegistrationInfoDto full = service.update(ORGANISATION, ORGANISER_ID, EVENT_ID,
                new RegistrationPeriodRequest(opened.toString(), NOW.plusDays(3).toString()));
        assertThat(full.registrationState()).isEqualTo("full");
        assertThat(full.confirmedCount()).isEqualTo(5);
        org.mockito.Mockito.verify(registrations, never()).save(any());
    }

    private static RegistrationPeriodRequest validRequest() {
        return new RegistrationPeriodRequest(NOW.plusDays(1).toString(), NOW.plusDays(4).toString());
    }
}
