package com.example.connect_sphere.equipment;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * D33: reserve several of a request's items for one window, all or nothing.
 * Each line reserves {@code quantity} units towards the request plus
 * {@code spare} (null = 0) extra units marked spare. For a serialised kind,
 * {@code serialNumbers} names the units, one per unit: the first
 * {@code quantity} are the requested units and the rest the spares. Left out,
 * the server picks free, working units.
 */
public record ReserveBatchRequest(UUID eventId, Instant start, Instant end, List<Line> lines) {

    public record Line(UUID equipmentId, Integer quantity, List<String> serialNumbers, Integer spare) {}
}
