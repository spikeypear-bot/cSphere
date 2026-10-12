package com.example.connect_sphere.safety;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "event_readiness_items")
public class ReadinessItemState {

    @Id
    @Column(name = "item_id")
    private UUID itemId;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "item_key", nullable = false)
    private String itemKey;

    @Column(nullable = false)
    private boolean checked;

    @Column(nullable = false, length = 2000)
    private String note;

    @Column(name = "checked_by")
    private UUID checkedBy;

    @Column(name = "checked_at")
    private OffsetDateTime checkedAt;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    protected ReadinessItemState() {
    }

    public ReadinessItemState(UUID eventId, String itemKey, boolean checked, String note,
            UUID updatedBy, OffsetDateTime updatedAt) {
        this.itemId = UUID.randomUUID();
        this.eventId = eventId;
        this.itemKey = itemKey;
        this.checked = checked;
        this.note = note;
        this.checkedBy = checked ? updatedBy : null;
        this.checkedAt = checked ? updatedAt : null;
        this.updatedBy = updatedBy;
        this.updatedAt = updatedAt;
    }

    public UUID getEventId() { return eventId; }
    public String getItemKey() { return itemKey; }
    public boolean isChecked() { return checked; }
    public String getNote() { return note; }
    public UUID getCheckedBy() { return checkedBy; }
    public OffsetDateTime getCheckedAt() { return checkedAt; }
    public UUID getUpdatedBy() { return updatedBy; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public void update(boolean nextChecked, String nextNote, UUID actor, OffsetDateTime at) {
        if (nextChecked && !checked) {
            checkedBy = actor;
            checkedAt = at;
        }
        checked = nextChecked;
        note = nextNote;
        updatedBy = actor;
        updatedAt = at;
    }
}
