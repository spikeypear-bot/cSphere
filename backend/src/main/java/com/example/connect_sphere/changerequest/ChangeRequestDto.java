package com.example.connect_sphere.changerequest;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.example.connect_sphere.eventrequest.dto.SaveEventRequestRequest;

/** EO03/EC-NEW3 request and response bodies. */
public final class ChangeRequestDto {

    private ChangeRequestDto() {
    }

    /** EO03: the organiser's proposal, as the full set of changeable details. */
    public record Create(UUID requestId, String reason, SaveEventRequestRequest details) {
    }

    /** EC-NEW3: approve, naming every impact the coordinator has considered. */
    public record Approve(List<String> acknowledged) {
    }

    /** EC-NEW3: reject, with a reason for the organiser. */
    public record Reject(String reason) {
    }

    /** One changed field, before and after, as stored values. */
    public record FieldChange(String field, String label, Object before, Object after) {
    }

    /**
     * EC-NEW3: one thing the change would affect. {@code status} is "ok" or
     * "attention"; every "attention" key must be acknowledged to approve.
     */
    public record ImpactItem(String key, String area, String status, String title, String detail) {
    }

    /**
     * A change request as one viewer may see it. {@code impact} is calculated
     * now, from current data, and only for the coordinator and Lead while it
     * is pending. {@code closedReason} explains a pending request that can no
     * longer be decided (e.g. the event has started).
     */
    public record View(
            UUID changeRequestId,
            UUID requestId,
            UUID eventId,
            String eventName,
            String organisation,
            String currentStatus,
            String status,
            String closedReason,
            String reason,
            String requestedByName,
            OffsetDateTime requestedAt,
            String decidedByName,
            OffsetDateTime decidedAt,
            String decisionReason,
            List<FieldChange> changes,
            List<ImpactItem> impact,
            /** Once approved: the impacts the coordinator acknowledged, still to follow up. */
            List<ImpactItem> followUps,
            boolean canDecide,
            boolean canWithdraw) {
    }
}
