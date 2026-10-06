package com.example.connect_sphere.venueavailability;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.connect_sphere.venue.repository.VenueRepository;
import com.example.connect_sphere.venue.service.VenueNotFoundException;
import com.example.connect_sphere.venuebooking.service.InvalidVenueBookingException;
import com.example.connect_sphere.venuebooking.service.VenueBookingStateException;
import com.example.connect_sphere.venueavailability.AvailabilityModels.*;

@Service
@Transactional(readOnly = true)
public class AvailabilityService {
    private final AvailabilityRepository repository;
    private final VenueRepository venues;
    public AvailabilityService(AvailabilityRepository repository, VenueRepository venues) {
        this.repository = repository; this.venues = venues;
    }
    private void requireVenue(UUID id) {
        if (!venues.existsById(id)) throw new VenueNotFoundException(id);
    }
    public List<Period> list(UUID id) { requireVenue(id); return repository.periods(id); }
    public Schedule schedule(UUID id) {
        requireVenue(id);
        return new Schedule(repository.settings(id), repository.bookings(id), repository.periods(id));
    }
    public Schedule schedule(UUID id, OffsetDateTime start, OffsetDateTime end) {
        if (start == null && end == null) return schedule(id);
        if (start == null || end == null || !end.isAfter(start)
                || java.time.Duration.between(start, end).compareTo(java.time.Duration.ofDays(93)) > 0)
            throw new InvalidVenueBookingException("Provide both start and end, with a positive schedule range of at most 93 days.");
        requireVenue(id);
        return new Schedule(repository.settings(id), repository.bookings(id, start, end), repository.periods(id, start, end));
    }
    public Settings settings(UUID id) { requireVenue(id); return repository.settings(id); }

    @Transactional
    public Settings updateSettings(UUID id, Settings settings) {
        venues.findForUpdate(id).orElseThrow(() -> new VenueNotFoundException(id));
        if (settings == null || settings.setupMinutes() == null || settings.turnaroundMinutes() == null
                || settings.setupMinutes() < 0 || settings.turnaroundMinutes() < 0
                || settings.setupMinutes() > 10080 || settings.turnaroundMinutes() > 10080)
            throw new InvalidVenueBookingException("Setup and turnaround must be whole minutes from 0 to 10080.");
        // Changing occupancy around existing commitments needs a separate impact-review workflow.
        if (!repository.bookings(id).isEmpty())
            throw new VenueBookingStateException("Setup and turnaround cannot be changed while this venue has active bookings.");
        repository.settings(id, settings);
        return settings;
    }

    public Preview preview(UUID id, Request r) {
        requireVenue(id); validate(r, java.time.LocalDate.now(java.time.ZoneId.of("Asia/Singapore")));
        if (repository.periods(id).stream().anyMatch(p -> overlaps(
                p.startDateTime(), p.endDateTime(), r.startDateTime(), r.endDateTime())))
            throw new VenueBookingStateException("This period overlaps an existing unavailable period for this venue. Choose dates and times outside the recorded periods.");
        List<Booking> affected = repository.bookings(id).stream()
                .filter(b -> overlaps(b.effectiveStart(), b.effectiveEnd(), r.startDateTime(), r.endDateTime())).toList();
        return new Preview(affected, token(r, affected));
    }

    @Transactional
    public Period create(UUID id, UUID actor, Request r) {
        venues.findForUpdate(id).orElseThrow(() -> new VenueNotFoundException(id));
        Preview p = preview(id, r);
        if (!p.affectedBookings().isEmpty() && (!p.previewToken().equals(r.previewToken())
                || r.acknowledgedBookingIds() == null
                || !r.acknowledgedBookingIds().containsAll(p.affectedBookings().stream().map(Booking::bookingId).toList())))
            throw new VenueBookingStateException("Affected bookings changed or need confirmation. Review the affected bookings again.");
        UUID key = UUID.randomUUID();
        repository.insert(key, id, actor, r, p.affectedBookings());
        return repository.periods(id).stream().filter(x -> x.unavailabilityId().equals(key)).findFirst().orElseThrow();
    }

    public boolean affected(UUID bookingId) { return repository.affected(bookingId); }

    public List<Booking> conflicts(UUID venueId, UUID eventId, OffsetDateTime start, OffsetDateTime end) {
        Settings s = repository.settings(venueId);
        return repository.bookings(venueId).stream().filter(b -> b.status().equals("approved") && !b.eventId().equals(eventId))
                .filter(b -> overlaps(b.effectiveStart(), b.effectiveEnd(), start.minusMinutes(s.setupMinutes()), end.plusMinutes(s.turnaroundMinutes())))
                .toList();
    }
    public boolean unavailable(UUID venueId, OffsetDateTime start, OffsetDateTime end) {
        Settings s = repository.settings(venueId);
        return repository.periods(venueId).stream().anyMatch(p -> overlaps(p.startDateTime(), p.endDateTime(),
                start.minusMinutes(s.setupMinutes()), end.plusMinutes(s.turnaroundMinutes())));
    }
    public static boolean overlaps(OffsetDateTime a, OffsetDateTime b, OffsetDateTime c, OffsetDateTime d) {
        return a.isBefore(d) && b.isAfter(c);
    }
    static void validate(Request r, java.time.LocalDate today) {
        if (r == null || r.startDateTime() == null || r.endDateTime() == null)
            throw new InvalidVenueBookingException("Start and end date/time are required.");
        if (r.startDateTime().atZoneSameInstant(java.time.ZoneId.of("Asia/Singapore")).toLocalDate().isBefore(today))
            throw new InvalidVenueBookingException("Start date cannot be before today (Singapore time).");
        if (!r.endDateTime().isAfter(r.startDateTime()))
            throw new InvalidVenueBookingException("End date/time must be strictly later than start date/time.");
        if (r.reason() == null || r.reason().isBlank() || r.reason().strip().length() > 2000)
            throw new InvalidVenueBookingException("Reason is required and must be at most 2000 characters.");
    }
    private static String token(Request r, List<Booking> bookings) {
        try {
            String value = r.startDateTime().toInstant() + "|" + r.endDateTime().toInstant() + "|" + r.reason().strip() + "|" + bookings;
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
