package com.example.connect_sphere.equipment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "equipment_logs")
public class EquipmentLog {

    @Id
    @Column(name = "log_id")
    private UUID id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "equipment_id", nullable = false)
    private UUID equipmentId;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "technical_requirements")
    private String technicalRequirements;

    // Set only when reserving a specific serialised unit; null for quantity-only reservations.
    @Column(name = "serial_number", length = SerialisedEquipmentId.MAX_SERIAL_NUMBER_LENGTH)
    private String serialNumber;

    @Column(name = "loaned_from", nullable = false)
    private Instant loanedFrom;

    @Column(name = "loaned_until", nullable = false)
    private Instant loanedUntil;

    /** V40 (D33): an extra unit held beyond what the event requested. */
    @Column(nullable = false)
    private boolean spare;

    /** V74 (D40): the request this was reserved for; null before V74. */
    @Column(name = "request_id")
    private UUID requestId;

    protected EquipmentLog() {}

    public EquipmentLog(UUID eventId, UUID equipmentId, int quantity,
                        String serialNumber, Instant loanedFrom, Instant loanedUntil) {
        this(eventId, equipmentId, quantity, serialNumber, loanedFrom, loanedUntil, false);
    }

    public EquipmentLog(UUID eventId, UUID equipmentId, int quantity,
                        String serialNumber, Instant loanedFrom, Instant loanedUntil, boolean spare) {
        this.id = UUID.randomUUID();
        this.eventId = eventId;
        this.equipmentId = equipmentId;
        this.quantity = quantity;
        this.serialNumber = serialNumber;
        this.loanedFrom = loanedFrom;
        this.loanedUntil = loanedUntil;
        this.spare = spare;
    }

    public UUID getId() { return id; }
    public UUID getEventId() { return eventId; }
    public UUID getEquipmentId() { return equipmentId; }
    public int getQuantity() { return quantity; }
    public String getSerialNumber() { return serialNumber; }
    public Instant getLoanedFrom() { return loanedFrom; }
    public Instant getLoanedUntil() { return loanedUntil; }
    public boolean isSpare() { return spare; }
    public UUID getRequestId() { return requestId; }

    /** D40: the request this reservation is held for. */
    public void forRequest(UUID requestId) {
        this.requestId = requestId;
    }

    /** TS09 AC10: the same reservation, now held by another unit of the kind. */
    public void replaceUnit(String replacementSerialNumber) {
        this.serialNumber = replacementSerialNumber;
    }

    /** EO07: held for part of [start, end) at least: it starts before the period ends and ends after it starts. */
    public boolean overlaps(Instant start, Instant end) {
        return loanedFrom.isBefore(end) && loanedUntil.isAfter(start);
    }

    /** TS06, D40: held for the whole of [start, end). */
    public boolean covers(Instant start, Instant end) {
        return !loanedFrom.isAfter(start) && !loanedUntil.isBefore(end);
    }

    /** TS09 AC11: fewer units of a non-serialised kind for this reservation. */
    public void reduceBy(int units) {
        if (units < 0 || units > quantity) {
            throw new IllegalArgumentException("Cannot reduce " + quantity + " by " + units);
        }
        this.quantity -= units;
    }
}