package com.example.connect_sphere.event.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.connect_sphere.common.status.JourneyStatus;
import com.example.connect_sphere.common.status.JourneyStatusResolver;
import com.example.connect_sphere.equipmentrequest.EquipmentRequestLine;
import com.example.connect_sphere.equipmentrequest.EquipmentRequestLineRepository;
import com.example.connect_sphere.equipmentrequest.EquipmentRequestRepository;
import com.example.connect_sphere.equipmentrequest.EquipmentRequestStatus;
import com.example.connect_sphere.event.dto.OrganiserArrangementsDto;
import com.example.connect_sphere.event.entity.Event;
import com.example.connect_sphere.event.repository.EventRepository;
import com.example.connect_sphere.user.entity.User;
import com.example.connect_sphere.user.repository.UserRepository;
import com.example.connect_sphere.venue.entity.Venue;
import com.example.connect_sphere.venue.repository.VenueRepository;
import com.example.connect_sphere.venueavailability.AvailabilityService;
import com.example.connect_sphere.venuebooking.entity.VenueBookingRecord;
import com.example.connect_sphere.venuebooking.entity.VenueBookingStatus;
import com.example.connect_sphere.venuebooking.repository.VenueBookingRecordRepository;

/**
 * EO04: the Event Organiser's view of their event's confirmed arrangements.
 *
 * <p>Before the first confirmation nothing about venues or equipment is shown
 * (it may still change). After it, the approved arrangement is shown; if the
 * event has since gone back to Planning, the same arrangement is shown as
 * being re-confirmed, with any venue that became unavailable marked as such.
 */
@Service
public class EventArrangementsService {

    private final EventRepository events;
    private final VenueBookingRecordRepository bookings;
    private final VenueRepository venues;
    private final EquipmentRequestRepository equipmentRequests;
    private final EquipmentRequestLineRepository equipmentLines;
    private final AvailabilityService availability;
    private final UserRepository users;
    private final JourneyStatusResolver statuses;

    public EventArrangementsService(EventRepository events, VenueBookingRecordRepository bookings,
            VenueRepository venues, EquipmentRequestRepository equipmentRequests,
            EquipmentRequestLineRepository equipmentLines, AvailabilityService availability, UserRepository users,
            JourneyStatusResolver statuses) {
        this.events = events;
        this.bookings = bookings;
        this.venues = venues;
        this.equipmentRequests = equipmentRequests;
        this.equipmentLines = equipmentLines;
        this.availability = availability;
        this.users = users;
        this.statuses = statuses;
    }

    /** Other organisations' events are reported as not found, so their
     * existence is not revealed (same rule as EO15). */
    @Transactional(readOnly = true)
    public OrganiserArrangementsDto forOrganiser(String organisation, UUID eventId) {
        Event event = events.findById(eventId)
                .filter(e -> organisation != null && organisation.equals(e.getOrganisation()))
                .orElseThrow(() -> new EventNotFoundException(eventId));
        JourneyStatus journey = statuses.journeyOf(event);
        String view = switch (journey) {
            case CONFIRMED -> "confirmed";
            case COMPLETED -> "completed";
            case CANCELLED -> "cancelled";
            default -> event.getFirstConfirmedAt() != null ? "reconfirming" : "not_confirmed";
        };
        boolean showArrangements = !view.equals("not_confirmed") && !view.equals("cancelled");

        User coordinator = event.getCoordinatorId() == null ? null
                : users.findById(event.getCoordinatorId()).orElse(null);
        return new OrganiserArrangementsDto(
                event.getEventId(), event.getEventName(), event.getStartDatetime(), event.getEndDatetime(),
                event.getExpectedAttendance(), statuses.forEvent(event), view,
                coordinator == null ? null : coordinator.getUsername(),
                coordinator == null ? null : coordinator.getEmail(),
                event.getAccessibilityNeeds() == null ? List.of() : List.copyOf(event.getAccessibilityNeeds()),
                showArrangements ? venuesOf(event) : List.of(),
                showArrangements ? equipmentOf(event) : List.of(),
                showArrangements ? event.getSafetyApprovedAt() : null,
                event.getFirstConfirmedAt(),
                event.getConfirmedAt());
    }

    private List<OrganiserArrangementsDto.Venue> venuesOf(Event event) {
        List<OrganiserArrangementsDto.Venue> result = new ArrayList<>();
        for (VenueBookingRecord booking : bookings.findForEvent(event.getEventId())) {
            if (booking.getStatus() != VenueBookingStatus.approved) {
                continue;
            }
            Venue venue = venues.findById(booking.getVenueId()).orElse(null);
            if (venue == null) {
                continue;
            }
            result.add(new OrganiserArrangementsDto.Venue(venue.getVenueId(), venue.getVenueAddress(),
                    venue.getVenueCapacity(), copy(venue.getSupportedLayouts()), copy(venue.getVenueAccessibilities()),
                    copy(venue.getVenueFacilities()), venue.getOperatingInformation(),
                    availability.affected(booking.getBookingId())));
        }
        return result;
    }

    /** Approved equipment only, merged by item so the organiser sees one line
     * per piece of equipment. TS09: less any shortfall recorded after
     * equipment was reported damaged; an item with nothing left is not shown. */
    private List<OrganiserArrangementsDto.Equipment> equipmentOf(Event event) {
        Map<String, Integer> quantities = new LinkedHashMap<>();
        java.util.Set<UUID> counted = new java.util.HashSet<>();
        equipmentRequests.findByEventId(event.getEventId()).stream()
                .filter(r -> r.getStatus() == EquipmentRequestStatus.approved)
                .flatMap(r -> equipmentLines.findByIdRequestId(r.getId()).stream())
                .forEach((EquipmentRequestLine line) -> {
                    int quantity = line.getQuantity();
                    if (shortfalls != null) {
                        // TS09: the ledger's need covers every approved line of the kind,
                        // less recorded shortfalls (as TS06 counts it), so each kind counts once.
                        UUID equipmentId = line.getId().getEquipmentId();
                        if (!counted.add(equipmentId)) {
                            return;
                        }
                        quantity = shortfalls.need(event.getEventId(), equipmentId, null);
                    }
                    quantities.merge(line.getEquipment().getName(), quantity, Integer::sum);
                });
        return quantities.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .map(e -> new OrganiserArrangementsDto.Equipment(e.getKey(), e.getValue())).toList();
    }

    /** TS09: recorded equipment shortfalls. Setter-injected so existing
     * constructions of this service keep working; absent in plain unit tests. */
    private com.example.connect_sphere.equipmentdamage.service.EquipmentShortfallLedger shortfalls;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setShortfalls(com.example.connect_sphere.equipmentdamage.service.EquipmentShortfallLedger shortfalls) {
        this.shortfalls = shortfalls;
    }

    private static List<String> copy(List<?> values) {
        return values == null ? List.of() : values.stream().map(String::valueOf).toList();
    }
}
