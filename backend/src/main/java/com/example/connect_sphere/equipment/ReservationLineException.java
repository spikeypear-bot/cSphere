package com.example.connect_sphere.equipment;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** D33: a reservation refused because of one item. The error body names the
 * item ({@code equipmentId}) so the console can mark the row that failed. */
public class ReservationLineException extends ResponseStatusException {

    private final transient UUID equipmentId;

    public ReservationLineException(HttpStatus status, String message, UUID equipmentId) {
        super(status, message);
        this.equipmentId = equipmentId;
    }

    public UUID getEquipmentId() {
        return equipmentId;
    }
}
