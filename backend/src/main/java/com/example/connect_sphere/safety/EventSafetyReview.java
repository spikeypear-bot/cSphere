package com.example.connect_sphere.safety;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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

    /** SO-C5/SO-C6 (V34): the checklist items a rejection or a request for
     * changes concerns; empty for an approval. */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "flagged_items", columnDefinition = "text[]", nullable = false)
    private List<String> flaggedItems = new ArrayList<>();

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
    public List<String> getFlaggedItems() { return flaggedItems; }

    public void update(String status, String reason, List<String> flagged, UUID reviewer, OffsetDateTime at) {
        reviewStatus = status;
        note = reason;
        flaggedItems = new ArrayList<>(flagged);
        reviewedBy = reviewer;
        reviewedAt = at;
    }
}
