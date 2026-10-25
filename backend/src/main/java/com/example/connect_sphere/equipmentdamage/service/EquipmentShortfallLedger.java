package com.example.connect_sphere.equipmentdamage.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import com.example.connect_sphere.equipmentdamage.entity.EquipmentDamageOutcome;
import com.example.connect_sphere.equipmentdamage.entity.ShortfallResolution;
import com.example.connect_sphere.equipmentdamage.repository.EquipmentDamageOutcomeRepository;
import com.example.connect_sphere.equipmentrequest.EquipmentRequest;
import com.example.connect_sphere.equipmentrequest.EquipmentRequestLine;
import com.example.connect_sphere.equipmentrequest.EquipmentRequestLineRepository;
import com.example.connect_sphere.equipmentrequest.EquipmentRequestRepository;
import com.example.connect_sphere.equipmentrequest.EquipmentRequestStateException;
import com.example.connect_sphere.equipmentrequest.EquipmentRequestStatus;

/**
 * TS09: the one place that knows how shortfalls change what an event is owed.
 *
 * <p>An event's <em>need</em> for an equipment kind is what its approved
 * requests asked for, less every shortfall recorded against it. Approved
 * additional requests (AC19) add their lines back; a shortfall the
 * coordinator accepted with a note (AC20) stays subtracted. EC07, TS06 and
 * EO04 read this so they agree on how much an event should have reserved.
 */
@Component
public class EquipmentShortfallLedger {

    /** No real outcome has this id, so {@link #need} counts every shortfall. */
    private static final UUID NONE = new UUID(0, 0);

    private final EquipmentDamageOutcomeRepository outcomes;
    private final EquipmentRequestRepository requests;
    private final EquipmentRequestLineRepository lines;
    private final Clock clock;

    public EquipmentShortfallLedger(EquipmentDamageOutcomeRepository outcomes, EquipmentRequestRepository requests,
            EquipmentRequestLineRepository lines, Clock clock) {
        this.outcomes = outcomes;
        this.requests = requests;
        this.lines = lines;
        this.clock = clock;
    }

    /** What the event is owed of this kind, leaving out {@code excludedOutcome}'s own shortfall. */
    public int need(UUID eventId, UUID equipmentId, UUID excludedOutcome) {
        int approved = requests.findByEventId(eventId).stream()
                .filter(r -> r.getStatus() == EquipmentRequestStatus.approved)
                .flatMap(r -> lines.findByIdRequestId(r.getId()).stream())
                .filter(line -> line.getId().getEquipmentId().equals(equipmentId))
                .mapToInt(EquipmentRequestLine::getQuantity)
                .sum();
        int short_ = outcomes.recordedShortfall(eventId, equipmentId, excludedOutcome == null ? NONE : excludedOutcome);
        return Math.max(0, approved - short_);
    }

    /** AC19: the shortfall an additional request is for. It must be this
     * event's, still open, and not already answered by a request awaiting
     * review or approved. */
    public EquipmentDamageOutcome requireOpenShortfall(UUID eventId, UUID outcomeId) {
        EquipmentDamageOutcome outcome = lockForEvent(eventId, outcomeId);
        if (!canRequestAdditional(outcome)) {
            throw new EquipmentRequestStateException(outcome.isOpenShortfall()
                    ? "An additional request for this shortfall is already awaiting review or approved."
                    : "This equipment shortfall is not open, so no additional request is needed.");
        }
        return outcome;
    }

    /** One of this event's damage outcomes, row-locked; 404 when it is not this event's. */
    public EquipmentDamageOutcome lockForEvent(UUID eventId, UUID outcomeId) {
        return outcomes.findForUpdate(outcomeId)
                .filter(o -> o.getEventId().equals(eventId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "That equipment shortfall was not found for this event."));
    }

    /** The status of the additional request raised for this outcome, if there is one. */
    public Optional<EquipmentRequestStatus> linkedStatus(EquipmentDamageOutcome outcome) {
        return Optional.ofNullable(outcome.getAdditionalRequestId())
                .flatMap(requests::findById)
                .map(EquipmentRequest::getStatus);
    }

    /** An open shortfall with no live request answering it. */
    public boolean canRequestAdditional(EquipmentDamageOutcome outcome) {
        return outcome.isOpenShortfall()
                && linkedStatus(outcome).map(status -> status == EquipmentRequestStatus.rejected).orElse(true);
    }

    public void linkAdditionalRequest(EquipmentDamageOutcome outcome, UUID requestId) {
        outcome.setAdditionalRequestId(requestId);
        outcomes.save(outcome);
    }

    /** AC19: Technical Support approved the additional request, so its
     * shortfall is dealt with. Requests not raised for a shortfall are ignored. */
    public void onRequestApproved(UUID requestId) {
        outcomes.findByAdditionalRequestId(requestId)
                .filter(EquipmentDamageOutcome::isOpenShortfall)
                .ifPresent(outcome -> {
                    outcome.setResolution(ShortfallResolution.ADDITIONAL_REQUEST);
                    outcome.setResolvedAt(Instant.now(clock));
                    outcomes.save(outcome);
                });
    }
}
