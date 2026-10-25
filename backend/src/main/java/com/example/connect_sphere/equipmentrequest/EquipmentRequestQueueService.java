package com.example.connect_sphere.equipmentrequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.example.connect_sphere.common.status.JourneyStatusResolver;
import com.example.connect_sphere.equipmentdamage.repository.EquipmentDamageOutcomeRepository;
import com.example.connect_sphere.event.entity.Event;
import com.example.connect_sphere.event.entity.EventStatus;
import com.example.connect_sphere.event.repository.EventRepository;
import com.example.connect_sphere.user.repository.UserRepository;

/**
 * Technical Support's list of equipment requests (TS01; extended for the
 * console, D32). One fixed number of queries however many requests there
 * are: requests, their events, lines (with equipment), coordinators and the
 * TS09 shortfall links are each loaded once.
 */
@Service
public class EquipmentRequestQueueService {

    /** EO07: a cancelled or finished event's request needs no review. */
    private static final Set<EventStatus> CLOSED = EnumSet.of(EventStatus.cancelled, EventStatus.completed);

    private final EquipmentRequestRepository requests;
    private final EquipmentRequestLineRepository lines;
    private final EventRepository events;
    private final UserRepository users;
    private final EquipmentDamageOutcomeRepository outcomes;
    private final JourneyStatusResolver statuses;

    public EquipmentRequestQueueService(EquipmentRequestRepository requests, EquipmentRequestLineRepository lines,
            EventRepository events, UserRepository users, EquipmentDamageOutcomeRepository outcomes,
            JourneyStatusResolver statuses) {
        this.requests = requests;
        this.lines = lines;
        this.events = events;
        this.users = users;
        this.outcomes = outcomes;
        this.statuses = statuses;
    }

    /**
     * Requests with this status ({@code processing}, {@code approved},
     * {@code rejected} or {@code all}; null means {@code processing}), for
     * events that are not cancelled or completed, soonest event first. 400
     * for any other status.
     */
    @Transactional(readOnly = true)
    public List<EquipmentRequestResponse> list(String status) {
        List<EquipmentRequest> found = requests.findByStatusIn(statusesFor(status));
        if (found.isEmpty()) {
            return List.of();
        }
        Map<UUID, Event> eventsById = events.findAllById(found.stream().map(EquipmentRequest::getEventId)
                        .collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(Event::getEventId, Function.identity()));
        List<EquipmentRequest> open = found.stream()
                .filter(r -> {
                    Event event = eventsById.get(r.getEventId());
                    return event == null || !CLOSED.contains(event.getStatus());
                })
                .toList();
        if (open.isEmpty()) {
            return List.of();
        }
        Set<UUID> requestIds = open.stream().map(EquipmentRequest::getId).collect(Collectors.toSet());

        Map<UUID, List<EquipmentRequestResponse.Item>> itemsByRequest = new HashMap<>();
        lines.findByRequestIds(requestIds).stream()
                .sorted(Comparator.comparing((EquipmentRequestLine l) -> l.getEquipment().getName()))
                .forEach(line -> itemsByRequest.computeIfAbsent(line.getId().getRequestId(), id -> new ArrayList<>())
                        .add(new EquipmentRequestResponse.Item(line.getId().getEquipmentId(),
                                line.getEquipment().getName(), line.getQuantity())));

        Set<UUID> coordinatorIds = eventsById.values().stream().map(Event::getCoordinatorId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, String> names = new HashMap<>();
        if (!coordinatorIds.isEmpty()) {
            users.findAllById(coordinatorIds).forEach(user -> names.put(user.getUserId(), user.getUsername()));
        }
        Set<UUID> shortfallRequests = new HashSet<>(outcomes.shortfallRequestIds(requestIds));

        return open.stream()
                .map(r -> toResponse(r, eventsById.get(r.getEventId()), itemsByRequest, names, shortfallRequests))
                .sorted(Comparator.comparing(EquipmentRequestResponse::eventStart,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(response -> response.requestId().toString()))
                .toList();
    }

    private EquipmentRequestResponse toResponse(EquipmentRequest r, Event event,
            Map<UUID, List<EquipmentRequestResponse.Item>> items, Map<UUID, String> names,
            Set<UUID> shortfallRequests) {
        Instant start = event != null && event.getStartDatetime() != null ? event.getStartDatetime().toInstant() : null;
        Instant end = event != null && event.getEndDatetime() != null ? event.getEndDatetime().toInstant() : null;
        UUID coordinator = event != null ? event.getCoordinatorId() : null;
        EquipmentRequestResponse.Person requestedBy = coordinator == null ? null
                : new EquipmentRequestResponse.Person(coordinator, names.get(coordinator));
        return new EquipmentRequestResponse(r.getId(), r.getEventId(),
                event != null ? event.getEventName() : "Unknown event", start, end,
                r.getTechnicalRequirement(), r.getStatus().name(),
                event != null ? event.getOrganisation() : null, requestedBy,
                shortfallRequests.contains(r.getId()), items.getOrDefault(r.getId(), List.of()),
                event != null ? statuses.journeyOf(event).name() : null);
    }

    private static Set<EquipmentRequestStatus> statusesFor(String status) {
        String value = status == null || status.isBlank() ? "processing" : status.strip().toLowerCase(Locale.ROOT);
        if ("all".equals(value)) {
            return EnumSet.allOf(EquipmentRequestStatus.class);
        }
        try {
            return EnumSet.of(EquipmentRequestStatus.valueOf(value));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "status must be processing, approved, rejected or all.");
        }
    }
}
