package com.example.connect_sphere.equipment;

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

    protected EquipmentLog() {}

    public EquipmentLog(UUID eventId, UUID equipmentId, int quantity,
                        String serialNumber, Instant loanedFrom, Instant loanedUntil) {
        this.id = UUID.randomUUID();
        this.eventId = eventId;
        this.equipmentId = equipmentId;
        this.quantity = quantity;
        this.serialNumber = serialNumber;
        this.loanedFrom = loanedFrom;
        this.loanedUntil = loanedUntil;
    }

    public UUID getId() { return id; }
    public UUID getEventId() { return eventId; }
    public UUID getEquipmentId() { return equipmentId; }
    public int getQuantity() { return quantity; }
    public String getSerialNumber() { return serialNumber; }
    public Instant getLoanedFrom() { return loanedFrom; }
    public Instant getLoanedUntil() { return loanedUntil; }

    /** TS09 AC10: the same reservation, now held by another unit of the kind. */
    public void replaceUnit(String replacementSerialNumber) {
        this.serialNumber = replacementSerialNumber;
    }

    /** TS09 AC11: fewer units of a non-serialised kind for this reservation. */
    public void reduceBy(int units) {
        if (units < 0 || units > quantity) {
            throw new IllegalArgumentException("Cannot reduce " + quantity + " by " + units);
        }
        this.quantity -= units;
    }
}