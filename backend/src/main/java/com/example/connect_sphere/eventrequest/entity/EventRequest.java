package com.example.connect_sphere.eventrequest.entity;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.example.connect_sphere.common.enums.AccessibilityFeature;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Maps to `event_requests` — see SCHEMA.md for the authoritative column-by-column
 * reference. A row with `eventId == null` is a *creation* request (EO01/EO02); one
 * with `eventId` set is an *amendment* request against an existing event (EO03,
 * out of scope for this slice).
 *
 * Ownership/visibility is scoped by `organisation` (free text, per SCHEMA.md gap
 * #2) rather than a real account — see docs/decision-log.md D6a: there is no
 * credential-based login yet, only the "Login as [Role]" selector, so this is the
 * interim scoping key until real accounts exist.
 */
@Entity
@Table(name = "event_requests")
@Getter
@Setter
public class EventRequest {

    @Id
    private UUID requestId;

    @Column(name = "request_type")
    private Character requestType;

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

    @Column(name = "venue_requirements")
    private String venueRequirements;

    @Column(name = "equipment_requirements")
    private String equipmentRequirements;

    // This column is text[], not the shared `accessibilities` enum-array type
    // — see V4__event_request_accessibility_needs_as_text.sql and
    // docs/decision-log.md for why: Hibernate 7's array machinery could not
    // bind a *named* Postgres enum array reliably (tried two approaches
    // against real Postgres; both failed), so this one column trades DB-level
    // enum enforcement for a mapping that actually works. Validity is still
    // enforced at the Java enum level. venues/events keep the real enum array.
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "accessibility_needs")
    private List<AccessibilityFeature> accessibilityNeeds = new ArrayList<>();

    @Column(name = "registration_needs")
    private Boolean registrationNeeds;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private EventRequestStatus status;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    // Set by EventRequestService on every save/submit, not a DB trigger — see
    // V7 migration and the service's applyFields()/submit() for the one place
    // this is written. Powers EO01/EO15's "last edited X minutes ago".
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    private String organisation;

    @Column(name = "created_by")
    private UUID createdBy;

    // EO19: set by EventRequestService.assignCoordinator(); null until an
    // Event Coordinator picks this request up. See V12 migration.
    @Column(name = "coordinator_id")
    private UUID coordinatorId;

    // EO09: set by EventRequestService.reject(); shown to the Event
    // Organiser on the rejection notification.
    @Column(name = "rejection_reason")
    private String rejectionReason;

    public EventRequest() {
    }
}
