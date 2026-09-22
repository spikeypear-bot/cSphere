package com.example.connect_sphere.event.entity;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Maps to `events` (V2__init_tables.sql, extended by V12 with
 * `coordinator_id`) — see SCHEMA.md for the authoritative reference.
 *
 * Created exactly once, by {@code EventRequestService.approve()}, from a
 * successfully-approved {@link com.example.connect_sphere.eventrequest.entity.EventRequest}
 * — this project has no other path that creates an event. Fields mirror the
 * request's own, since {@code events} "mirrors most of event_requests"
 * (SCHEMA.md §1); the two are deliberately not the same Java class — see
 * decision-log.md D16 on why inheritance is not used here either: an event
 * and its originating request do not share behaviour at a common call site,
 * only shape, and copying a handful of fields on approval is simpler than
 * modelling a shared supertype for a copy that happens once.
 */
@Entity
@Table(name = "events")
@Getter
@Setter
public class Event {

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "event_name")
    private String eventName;

    private String purpose;

    private String description;

    @Column(name = "start_datetime")
    private OffsetDateTime startDatetime;

    @Column(name = "end_datetime")
    private OffsetDateTime endDatetime;

    @Column(name = "expected_attendance")
    private Integer expectedAttendance;

    @Column(name = "venue_id")
    private UUID venueId;

    // Real accessibilities[] enum array (unlike event_requests.accessibility_needs,
    // which is text[] — see that entity's own comment for why). Matches Venue's
    // working pattern: Hibernate binds a plain List<String>, Postgres enforces
    // the enum on write via the explicit cast.
    @JdbcTypeCode(SqlTypes.ARRAY)
    @ColumnTransformer(write = "cast(? as accessibilities[])")
    @Column(name = "accessibility_needs", columnDefinition = "accessibilities[]")
    private List<String> accessibilityNeeds = new ArrayList<>();

    @Column(name = "registration_needs")
    private Boolean registrationNeeds;

    private String organisation;

    @Column(name = "actual_attendance")
    private Integer actualAttendance;

    @Column(name = "venue_requirements")
    private String venueRequirements;

    @Column(name = "equipment_requirements")
    private String equipmentRequirements;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private EventStatus status;

    // EO19: who to contact about this event once it exists — carried over
    // from event_requests.coordinator_id at approval time (see
    // EventRequestService.approve()), not reassigned independently here.
    @Column(name = "coordinator_id")
    private UUID coordinatorId;

    public Event() {
    }
}
