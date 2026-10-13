package com.example.connect_sphere.safety;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "event_safety_reviews")
public class EventSafetyReview {

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "review_status", nullable = false)
    private String reviewStatus;

    @Column(nullable = false, length = 2000)
    private String note;

    @Column(name = "reviewed_by", nullable = false)
    private UUID reviewedBy;

    @Column(name = "reviewed_at", nullable = false)
    private OffsetDateTime reviewedAt;

    protected EventSafetyReview() {
    }

    public EventSafetyReview(UUID eventId, String reviewStatus, String note, UUID reviewedBy,
            OffsetDateTime reviewedAt) {
        this.eventId = eventId;
        this.reviewStatus = reviewStatus;
        this.note = note;
        this.reviewedBy = reviewedBy;
        this.reviewedAt = reviewedAt;
    }

    public String getReviewStatus() { return reviewStatus; }
    public String getNote() { return note; }
    public UUID getReviewedBy() { return reviewedBy; }
    public OffsetDateTime getReviewedAt() { return reviewedAt; }

    public void update(String status, String reason, UUID reviewer, OffsetDateTime at) {
        reviewStatus = status;
        note = reason;
        reviewedBy = reviewer;
        reviewedAt = at;
    }
}
