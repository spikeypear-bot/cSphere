package com.example.connect_sphere.equipment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.example.connect_sphere.testsupport.FlowSupport;
import com.example.connect_sphere.user.repository.UserRepository;

import jakarta.persistence.EntityManager;

/**
 * EO07 AC8 / decision-log D28: equipment reserved for an event that has been
 * cancelled (or has finished) is available to other events for that period
 * again. The reservation rows are kept as history; only the overlap queries
 * that availability is computed from stop counting them. Real PostgreSQL;
 * every test rolls back.
 *
 * <p>The event's status is set directly here: this proves the availability
 * rule on its own, whichever way an event becomes cancelled.
 * {@code EventCancellationFlowTest} proves it again through the real
 * cancellation flow.
 */
@Tag("integration")
@Tag("EO07")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CancelledEventEquipmentAvailabilityTest {

    private static final Instant START = Instant.parse("2027-03-10T01:00:00Z");
    private static final Instant END = Instant.parse("2027-03-10T04:00:00Z");

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired EquipmentLogRepository logs;

    private FlowSupport flow;
    private UUID equipmentId;
    private UUID eventId;

    @BeforeEach
    void setUp() throws Exception {
        flow = new FlowSupport(mvc, users);
        eventId = flow.approvedEvent("eo1", "ec1", FlowSupport.requestBody("Projector Night", 100, "none"));
        entityManager.flush();
        equipmentId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO equipments (equipment_id, equipment_name, equipment_qty, serialised, equipment_type)
                VALUES (?, 'EO07 projector', 2, true, 'A')
                """, equipmentId);
        jdbc.update("""
                INSERT INTO serialised_equipments (equipment_id, serial_number, status)
                VALUES (?, 'EO07-P1', 'AVAILABLE'::equipment_status)
                """, equipmentId);
        // Both units out to this event for the whole period.
        jdbc.update("""
                INSERT INTO equipment_logs (log_id, event_id, equipment_id, quantity, serial_number, loaned_from, loaned_until)
                VALUES (?, ?, ?, 1, 'EO07-P1', ?, ?)
                """, UUID.randomUUID(), eventId, equipmentId, java.sql.Timestamp.from(START), java.sql.Timestamp.from(END));
        jdbc.update("""
                INSERT INTO equipment_logs (log_id, event_id, equipment_id, quantity, serial_number, loaned_from, loaned_until)
                VALUES (?, ?, ?, 1, NULL, ?, ?)
                """, UUID.randomUUID(), eventId, equipmentId, java.sql.Timestamp.from(START), java.sql.Timestamp.from(END));
    }

    private void setEventStatus(String status) {
        entityManager.flush();
        jdbc.update("UPDATE events SET status = ?::event_status WHERE event_id = ?", status, eventId);
        entityManager.clear();
    }

    private int logRows() {
        return jdbc.queryForObject("SELECT count(*) FROM equipment_logs WHERE event_id = ?", Integer.class, eventId);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"pending", "awaiting_safety_review", "safety_changes_requested", "safety_approved", "confirmed"})
    @DisplayName("EO07 AC8 (conflict): equipment reserved for an active event still blocks the same period")
    void anActiveEventStillHoldsItsEquipment(String status) {
        setEventStatus(status);

        assertThat(logs.findOverlappingForEquipment(equipmentId, START, END)).hasSize(2);
        assertThat(logs.findOverlappingForUnit(equipmentId, "EO07-P1", START, END)).hasSize(1);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"cancelled", "completed"})
    @DisplayName("EO07 AC8 (happy): equipment reserved for a cancelled or completed event is available for that period again, and its reservations are kept")
    void aCancelledOrCompletedEventNoLongerHoldsItsEquipment(String status) {
        setEventStatus(status);

        assertThat(logs.findOverlappingForEquipment(equipmentId, START, END)).isEmpty();
        assertThat(logs.findOverlappingForUnit(equipmentId, "EO07-P1", START, END)).isEmpty();
        assertThat(logRows()).isEqualTo(2);
    }

    @Test
    @DisplayName("EO07 AC8 (boundary): only the cancelled event's reservations stop counting; another event's overlapping reservation still does")
    void onlyTheCancelledEventsReservationsStopCounting() throws Exception {
        UUID otherEvent = flow.approvedEvent("eo3", "ec2", FlowSupport.requestBody("Other Night", 50, "none"));
        entityManager.flush();
        UUID otherLog = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO equipment_logs (log_id, event_id, equipment_id, quantity, serial_number, loaned_from, loaned_until)
                VALUES (?, ?, ?, 1, NULL, ?, ?)
                """, otherLog, otherEvent, equipmentId, java.sql.Timestamp.from(START), java.sql.Timestamp.from(END));
        setEventStatus("cancelled");

        List<EquipmentLog> counted = logs.findOverlappingForEquipment(equipmentId, START, END);

        assertThat(counted).extracting(EquipmentLog::getId).containsExactly(otherLog);
    }
}
