package com.example.connect_sphere.venuebooking.entity;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.*;
import lombok.Getter;

/** VS16 read mapping of current events, never event_requests or pending amendments. */
@Entity
@Table(name = "events")
@Immutable
@Getter
public class VenueEvent {
    @Id
    @Column(name = "event_id")
    private UUID eventId;
    @Column(name = "event_name")
    private String eventName;
    @Column(name = "start_datetime")
    private OffsetDateTime startDatetime;
    @Column(name = "end_datetime")
    private OffsetDateTime endDatetime;
    @Column(name = "expected_attendance")
    private Integer expectedAttendance;
    @Column(name = "venue_requirements", columnDefinition = "text")
    private String venueRequirements;
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "accessibility_needs", columnDefinition = "accessibilities[]")
    private List<String> accessibilityNeeds;
    @Column(name = "equipment_requirements", columnDefinition = "text")
    private String equipmentRequirements;
}
