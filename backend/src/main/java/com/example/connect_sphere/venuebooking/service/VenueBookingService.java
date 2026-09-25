package com.example.connect_sphere.venuebooking.service;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.connect_sphere.venue.mapper.VenueMapper;
import com.example.connect_sphere.venue.repository.VenueRepository;
import com.example.connect_sphere.venue.service.VenueNotFoundException;
import com.example.connect_sphere.venuebooking.dto.VenueBookingDto;
import com.example.connect_sphere.venuebooking.entity.VenueBooking;
import com.example.connect_sphere.venuebooking.entity.VenueBookingStatus;
import com.example.connect_sphere.venuebooking.repository.VenueBookingRepository;

@Service
@Transactional(readOnly = true)
public class VenueBookingService {
    private final VenueBookingRepository bookings;
    private final VenueRepository venues;
    private final VenueMapper venueMapper;

    public VenueBookingService(VenueBookingRepository bookings, VenueRepository venues, VenueMapper venueMapper) {
        this.bookings = bookings;
        this.venues = venues;
        this.venueMapper = venueMapper;
    }

    public List<VenueBookingDto> listPending() {
        return bookings.findByStatusOrderByEventStartDatetimeAscBookingIdAsc(VenueBookingStatus.pending)
                .stream().map(this::toDto).toList();
    }

    public VenueBookingDto get(UUID bookingId) {
        return toDto(bookings.findById(bookingId)
                .orElseThrow(() -> new VenueBookingNotFoundException(bookingId)));
    }

    public List<VenueBookingDto> listForVenue(UUID venueId) {
        if (!venues.existsById(venueId)) throw new VenueNotFoundException(venueId);
        return bookings.findByVenueVenueIdOrderByEventStartDatetimeAscBookingIdAsc(venueId)
                .stream().map(this::toDto).toList();
    }

    private VenueBookingDto toDto(VenueBooking booking) {
        var event = booking.getEvent();
        return new VenueBookingDto(booking.getBookingId(), booking.getStatus(),
                venueMapper.toDto(booking.getVenue()), new VenueBookingDto.EventRequirements(
                        event.getEventId(), event.getEventName(), event.getStartDatetime(), event.getEndDatetime(),
                        event.getExpectedAttendance(), event.getVenueRequirements(),
                        List.copyOf(event.getAccessibilityNeeds()), event.getEquipmentRequirements()));
    }
}
