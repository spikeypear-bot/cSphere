package com.example.connect_sphere.equipmentrequest;

import java.util.UUID;

/** TS11 AC1: published when a coordinator's equipment request is saved, so
 * Technical Support can be told once it commits. */
public record EquipmentRequestSubmitted(UUID requestId, UUID eventId) {
}
