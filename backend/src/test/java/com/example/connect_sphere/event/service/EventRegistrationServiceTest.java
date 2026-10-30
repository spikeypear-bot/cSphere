package com.example.connect_sphere.event.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

/**
 * EO10: registration information is owner-only, counts only confirmed places, and reports state.
 * EO11: the owner can set the registration capacity (positive whole number, not below confirmed count).
 */
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
        // forOrganiser (EO10) reads with findById; updateCapacity (EO11) takes the row lock.
        when(events.findById(id)).thenReturn(Optional.of(event));
        when(events.findForUpdate(id)).thenReturn(Optional.of(event));
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

    // ---------------------------------------------------------------- EO10

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
        event.setStatus(EventStatus.cancelled);
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

    // ---------------------------------------------------------------- EO11

    @Test
    @Tag("EO11")
    void EO11_validCapacityIsSavedAndReturned() {
        when(regs.findByEventIdOrderByRegisteredAtAsc(id)).thenReturn(List.of(reg("confirmed"), reg("withdrawn")));
        RegistrationInfoDto dto = service.updateCapacity(ACME, id, 5);
        assertThat(event.getRegistrationCapacity()).isEqualTo(5);
        assertThat(dto.capacity()).isEqualTo(5);
        assertThat(dto.placesRemaining()).isEqualTo(4);
    }

    @Test
    @Tag("EO11")
    void EO11_updateTakesTheEventRowLock() {
        // Same lock as attendee registration, so a registration cannot land between the count and the save.
        when(regs.findByEventIdOrderByRegisteredAtAsc(id)).thenReturn(List.of());
        service.updateCapacity(ACME, id, 5);
        verify(events).findForUpdate(id);
    }

    @Test
    @Tag("EO11")
    void EO11_invalidInputsAreRejectedAndCapacityUnchanged() {
        for (Object bad : new Object[] { 0, -3, 2.5, "ten", null }) {
            assertThatThrownBy(() -> service.updateCapacity(ACME, id, bad))
                    .isInstanceOf(EventLifecycleValidationException.class);
        }
        assertThat(event.getRegistrationCapacity()).isEqualTo(2);
        verify(events, never()).save(any());
    }

    @Test
    @Tag("EO11")
    void EO11_cannotGoBelowConfirmedButEqualIsAllowed() {
        when(regs.findByEventIdOrderByRegisteredAtAsc(id))
                .thenReturn(List.of(reg("confirmed"), reg("confirmed"), reg("withdrawn")));
        assertThatThrownBy(() -> service.updateCapacity(ACME, id, 1))
                .isInstanceOf(EventLifecycleValidationException.class);
        assertThat(event.getRegistrationCapacity()).isEqualTo(2);
        verify(events, never()).save(any());
        assertThat(service.updateCapacity(ACME, id, 2).registrationState()).isEqualTo("full");
    }

    @Test
    @Tag("EO11")
    void EO11_otherOrganisationOrNoRegistrationRefused() {
        assertThatThrownBy(() -> service.updateCapacity("Other", id, 5)).isInstanceOf(EventNotFoundException.class);
        event.setRegistrationNeeds(false);
        assertThatThrownBy(() -> service.updateCapacity(ACME, id, 5)).isInstanceOf(EventLifecycleException.class);
        assertThat(event.getRegistrationCapacity()).isEqualTo(2);
        verify(events, never()).save(any());
    }

    @Test
    @Tag("EO11")
    void EO11_tooLargeNumbersAreRejected() {
        // registration_capacity is a Postgres integer, so these must give a clean 422, not a 500
        for (Object bad : new Object[] { 2147483648L, Long.MAX_VALUE, "9999999999999999999" }) {
            assertThatThrownBy(() -> service.updateCapacity(ACME, id, bad))
                    .isInstanceOf(EventLifecycleValidationException.class);
        }
        assertThat(event.getRegistrationCapacity()).isEqualTo(2);
    }

    @Test
    @Tag("EO11")
    void EO11_otherNonNumericInputIsRejected() {
        for (Object bad : new Object[] { "", "   ", true, 3.0, "1e3" }) {
            assertThatThrownBy(() -> service.updateCapacity(ACME, id, bad))
                    .isInstanceOf(EventLifecycleValidationException.class);
        }
        assertThat(event.getRegistrationCapacity()).isEqualTo(2);
    }

    @Test
    @Tag("EO11")
    void EO11_cancelledEventRefusesCapacityChange() {
        event.setStatus(EventStatus.cancelled);
        assertThatThrownBy(() -> service.updateCapacity(ACME, id, 10))
                .isInstanceOf(EventLifecycleException.class);
        assertThat(event.getRegistrationCapacity()).isEqualTo(2);
        verify(events, never()).save(any());
    }

    @Test
    @Tag("EO11")
    void EO11_missingEventIsNotFound() {
        UUID unknown = UUID.randomUUID();
        when(events.findForUpdate(unknown)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.updateCapacity(ACME, unknown, 5))
                .isInstanceOf(EventNotFoundException.class);
    }
}