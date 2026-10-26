package com.example.connect_sphere.changerequest;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** VS17: historical venue requirements for one approved change and booking.
 * Notification read state never resolves this review. */
@Entity
@Table(name = "venue_requirement_reviews")
@Getter
@Setter
public class VenueRequirementReview {
    @Id private UUID reviewId;
    private UUID changeRequestId;
    private UUID bookingId;
    private UUID eventId;
    private UUID venueId;
    private UUID coordinatorId;
    private String coordinatorName;
    private String coordinatorEmail;
    private String eventName;
    private String venueAddress;
    private OffsetDateTime approvedAt;
    private UUID approvedBy;
    private OffsetDateTime reservedStart;
    private OffsetDateTime reservedEnd;
    private int setupDurationMinutes;
    private int turnaroundDurationMinutes;
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> previousRequirements;
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> newRequirements;
    private String status = "pending";
    private UUID reviewedBy;
    private String reviewerName;
    private OffsetDateTime reviewedAt;
    private String reviewNote;
    @JdbcTypeCode(SqlTypes.JSON)
    private java.util.List<Map<String, String>> reviewedChecks;
}
