package com.example.connect_sphere.venueissue.service;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.connect_sphere.venue.repository.VenueRepository;
import com.example.connect_sphere.venue.entity.Venue;
import com.example.connect_sphere.venue.service.VenueNotFoundException;
import com.example.connect_sphere.venuebooking.entity.VenueBookingStatus;
import com.example.connect_sphere.venuebooking.repository.VenueBookingRecordRepository;
import com.example.connect_sphere.venueissue.dto.CoordinatorVenueOperationalIssueDto.OverlappingEventDto;
import com.example.connect_sphere.venueissue.dto.CoordinatorVenueOperationalIssueDto;
import com.example.connect_sphere.venueissue.dto.CreateVenueOperationalIssueRequest;
import com.example.connect_sphere.venueissue.dto.VenueOperationalIssueDto;
import com.example.connect_sphere.venueissue.entity.VenueOperationalIssue;
import com.example.connect_sphere.venueissue.repository.VenueOperationalIssueRepository;

@Service
public class VenueOperationalIssueService {
    private static final int MAX_DESCRIPTION_LENGTH = 2000;

    private final VenueOperationalIssueRepository issues;
    private final VenueRepository venues;
    private final VenueBookingRecordRepository bookings;

    public VenueOperationalIssueService(
            VenueOperationalIssueRepository issues,
            VenueRepository venues,
            VenueBookingRecordRepository bookings) {
        this.issues = issues;
        this.venues = venues;
        this.bookings = bookings;
    }

    @Transactional
    public VenueOperationalIssueDto create(
            UUID venueId, UUID userId, CreateVenueOperationalIssueRequest request) {
        if (!venues.existsById(venueId)) {
            throw new VenueNotFoundException(venueId);
        }
        validate(request);
        VenueOperationalIssue issue = new VenueOperationalIssue(
                venueId,
                request.description().strip(),
                request.affectedFrom(),
                request.affectedUntil(),
                userId);
        return VenueOperationalIssueDto.from(issues.save(issue));
    }

    @Transactional(readOnly = true)
    public List<VenueOperationalIssueDto> list(UUID venueId) {
        if (!venues.existsById(venueId)) {
            throw new VenueNotFoundException(venueId);
        }
        return issues.findByVenueIdOrderByAffectedFromAscCreatedAtDesc(venueId)
                .stream().map(VenueOperationalIssueDto::from).toList();
    }

    @Transactional(readOnly = true)
    public List<CoordinatorVenueOperationalIssueDto> listForCoordinator(UUID coordinatorId) {
        List<VenueBookingRecordRepository.CoordinatorBooking> managedBookings =
                bookings.findConfirmedForCoordinator(coordinatorId, VenueBookingStatus.confirmed);
        Set<UUID> venueIds = managedBookings.stream()
                .map(VenueBookingRecordRepository.CoordinatorBooking::getVenueId)
                .collect(Collectors.toSet());
        if (venueIds.isEmpty()) {
            return List.of();
        }

        Map<UUID, String> addresses = venues.findAllById(venueIds).stream()
                .collect(Collectors.toMap(Venue::getVenueId, Venue::getVenueAddress));
        Map<UUID, List<VenueBookingRecordRepository.CoordinatorBooking>> bookingsByVenue =
                managedBookings.stream().collect(Collectors.groupingBy(
                        VenueBookingRecordRepository.CoordinatorBooking::getVenueId));

        return issues.findByVenueIdInOrderByAffectedFromAscCreatedAtDesc(List.copyOf(venueIds)).stream()
                .map(issue -> new CoordinatorVenueOperationalIssueDto(
                        issue.getIssueId(),
                        issue.getVenueId(),
                        addresses.getOrDefault(issue.getVenueId(), "Unknown venue"),
                        issue.getDescription(),
                        issue.getAffectedFrom(),
                        issue.getAffectedUntil(),
                        issue.getCreatedAt(),
                        overlappingEvents(issue, bookingsByVenue.getOrDefault(issue.getVenueId(), List.of()))))
                .toList();
    }

    private static List<OverlappingEventDto> overlappingEvents(
            VenueOperationalIssue issue,
            List<VenueBookingRecordRepository.CoordinatorBooking> managedBookings) {
        if (issue.getAffectedFrom() == null || issue.getAffectedUntil() == null) {
            return List.of();
        }
        return managedBookings.stream()
                .filter(booking -> booking.getStartDatetime() != null && booking.getEndDatetime() != null)
                .filter(booking -> booking.getStartDatetime().isBefore(issue.getAffectedUntil())
                        && booking.getEndDatetime().isAfter(issue.getAffectedFrom()))
                .map(booking -> new OverlappingEventDto(
                        booking.getEventId(),
                        booking.getEventName(),
                        booking.getStartDatetime(),
                        booking.getEndDatetime()))
                .toList();
    }

    private static void validate(CreateVenueOperationalIssueRequest request) {
        if (request == null || request.description() == null || request.description().isBlank()) {
            throw new InvalidVenueOperationalIssueException("Issue description is required.");
        }
        if (request.description().strip().length() > MAX_DESCRIPTION_LENGTH) {
            throw new InvalidVenueOperationalIssueException(
                    "Issue description must be at most " + MAX_DESCRIPTION_LENGTH + " characters.");
        }
        OffsetDateTime from = request.affectedFrom();
        OffsetDateTime until = request.affectedUntil();
        if ((from == null) != (until == null)) {
            throw new InvalidVenueOperationalIssueException(
                    "Affected start and end date/time must be supplied together.");
        }
        if (from != null && !until.isAfter(from)) {
            throw new InvalidVenueOperationalIssueException(
                    "Affected end date/time must be after the start date/time.");
        }
    }
}
