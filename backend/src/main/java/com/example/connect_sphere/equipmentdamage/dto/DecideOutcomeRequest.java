package com.example.connect_sphere.equipmentdamage.dto;

/**
 * TS09 AC9–AC12: what Technical Support decides for one affected event.
 * {@code decision} is one of:
 * <ul>
 *   <li>{@code COVERED}: serialised; the event's spares still cover it (AC9);</li>
 *   <li>{@code REPLACE}: serialised; swap in {@code replacementSerialNumber} (AC10);</li>
 *   <li>{@code SHORT}: serialised; no replacement, so the event is short (AC12);</li>
 *   <li>{@code REDUCE}: non-serialised; take {@code reduceBy} units (0 or more)
 *       off this event's reservations; the outcome follows from what is left (AC11, AC12).</li>
 * </ul>
 */
public record DecideOutcomeRequest(String decision, String replacementSerialNumber, Integer reduceBy) {
}
