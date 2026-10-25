package com.example.connect_sphere.event.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.example.connect_sphere.event.dto.RegistrationInfoDto;
import com.example.connect_sphere.event.entity.Event;
import com.example.connect_sphere.event.entity.EventRegistration;
import com.example.connect_sphere.event.entity.EventStatus;
import com.example.connect_sphere.event.repository.EventRegistrationRepository;
import com.example.connect_sphere.event.repository.EventRepository;

/** EO10: registration information is owner-only, counts only confirmed places, and reports state. */
@Tag("unit")
@Tag("EO10")
class EventRegistrationServiceTest {

    private static final String ACME = "Acme Pte Ltd";
    private final EventRepository events = mock(EventRepository.class);
    private final EventRegistrationRepository regs = mock(EventRegistrationRepository.class);
    private final EventRegistrationService service = new EventRegistrationService(events, regs);
    private final UUID id = UUID.randomUUID();
    private Event event;

    @BeforeEach
    void setUp() {
        event = new Event();
        event.setEventId(id);
        event.setEventName("Fair");
        event.setOrganisation(ACME);
        event.setRegistrationNeeds(true);
        event.setRegistrationCapacity(2);
        when(events.findById(id)).thenReturn(Optional.of(event));
    }

    private static EventRegistration reg(String status) {
        EventRegistration r = new EventRegistration();
        r.setRegistrationId(UUID.randomUUID());
        r.setAttendeeName("A");
        r.setReference("R-" + UUID.randomUUID());
        r.setRegisteredAt(OffsetDateTime.now());
        r.setStatus(status);
        return r;
    }

    @Test
    void EO10_AC1_otherOrganisationIsRefused() {
        assertThatThrownBy(() -> service.forOrganiser("Other", id)).isInstanceOf(EventNotFoundException.class);
    }

    @Test
    void EO10_AC4_onlyConfirmedConsumePlacesAndFullIsReported() {
        when(regs.findByEventIdOrderByRegisteredAtAsc(id))
                .thenReturn(List.of(reg("confirmed"), reg("withdrawn"), reg("event_cancelled"), reg("confirmed")));
        RegistrationInfoDto dto = service.forOrganiser(ACME, id);
        assertThat(dto.confirmedCount()).isEqualTo(2);
        assertThat(dto.placesRemaining()).isZero();
        assertThat(dto.registrationState()).isEqualTo("full");
        assertThat(dto.attendees()).hasSize(4);
    }

    @Test
    void EO10_AC5_statesFollowOpeningAndClosing() {
        event.setRegistrationOpensAt(OffsetDateTime.now().plusDays(1));
        assertThat(service.forOrganiser(ACME, id).registrationState()).isEqualTo("not_yet_open");
        event.setRegistrationOpensAt(OffsetDateTime.now().minusDays(2));
        event.setStartDatetime(OffsetDateTime.now().minusDays(1));
        assertThat(service.forOrganiser(ACME, id).registrationState()).isEqualTo("closed");
    }

    @Test
    void EO10_AC6_notRequiredAndEmptyMessages() {
        assertThat(service.forOrganiser(ACME, id).message()).isEqualTo("No registrations yet.");
        event.setRegistrationNeeds(false);
        assertThat(service.forOrganiser(ACME, id).registrationState()).isEqualTo("not_required");
    }

    @Test
    void EO10_AC3_AC4_cancelledEventReleasesConfirmedRegistrations() {
        event.setStatus(com.example.connect_sphere.event.entity.EventStatus.cancelled);
        when(regs.findByEventIdOrderByRegisteredAtAsc(id)).thenReturn(List.of(reg("confirmed")));
        RegistrationInfoDto dto = service.forOrganiser(ACME, id);
        assertThat(dto.confirmedCount()).isZero();
        assertThat(dto.placesRemaining()).isEqualTo(2);
        assertThat(dto.attendees().get(0).status()).isEqualTo("event_cancelled");
    }

    @Test
    void EO10_AC5_openWhenWindowStartedAndEventNotStarted() {
    event.setRegistrationOpensAt(OffsetDateTime.now().minusDays(1));
    event.setStartDatetime(OffsetDateTime.now().plusDays(5));
    when(regs.findByEventIdOrderByRegisteredAtAsc(id)).thenReturn(List.of(reg("confirmed")));
    assertThat(service.forOrganiser(ACME, id).registrationState()).isEqualTo("open");
}

    @Test
    void EO10_AC4_placesRemainingNeverNegative() {
        event.setRegistrationCapacity(1);
        when(regs.findByEventIdOrderByRegisteredAtAsc(id))
            .thenReturn(List.of(reg("confirmed"), reg("confirmed")));
        assertThat(service.forOrganiser(ACME, id).placesRemaining()).isZero();
    }

    @Test
    void EO10_AC3_cancelledEventKeepsWithdrawnAsWithdrawn() {
        event.setStatus(EventStatus.cancelled);
        when(regs.findByEventIdOrderByRegisteredAtAsc(id)).thenReturn(List.of(reg("withdrawn")));
        assertThat(service.forOrganiser(ACME, id).attendees().get(0).status()).isEqualTo("withdrawn");
    }
}
