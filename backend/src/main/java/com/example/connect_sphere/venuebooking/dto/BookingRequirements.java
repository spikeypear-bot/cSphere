package com.example.connect_sphere.venuebooking.dto;

import java.util.List;
import com.example.connect_sphere.event.entity.Event;
import com.example.connect_sphere.common.enums.AccessibilityFeature;
import com.example.connect_sphere.common.enums.Facility;
import com.example.connect_sphere.venuebooking.service.InvalidVenueBookingException;

/** Null fields inherit the current event requirement; explicit lists apply to this room only. */
public record BookingRequirements(Integer expectedAttendance, List<String> requiredFacilities,
        List<String> accessibilityNeeds, String venueRequirements) {
    public void validate() {
        if (expectedAttendance != null && expectedAttendance < 1)
            throw new InvalidVenueBookingException("Booking attendance must be at least 1.");
        validateValues(requiredFacilities, java.util.Arrays.stream(Facility.values()).map(Enum::name).toList(), "facilities");
        validateValues(accessibilityNeeds, java.util.Arrays.stream(AccessibilityFeature.values()).map(Enum::name).toList(), "accessibility needs");
        if (accessibilityNeeds != null && accessibilityNeeds.contains("none") && accessibilityNeeds.size()>1)
            throw new InvalidVenueBookingException("None cannot be combined with other accessibility needs.");
        if (venueRequirements != null && venueRequirements.length()>2000)
            throw new InvalidVenueBookingException("Booking requirements must be 2000 characters or fewer.");
    }
    private static void validateValues(List<String> values,List<String> allowed,String label) {
        if(values!=null && (values.stream().anyMatch(v -> v==null || !allowed.contains(v)) || values.stream().distinct().count()!=values.size()))
            throw new InvalidVenueBookingException("Select valid, distinct booking " + label + ".");
    }
    public Event apply(Event source) {
        Event copy = new Event();
        copy.setExpectedAttendance(expectedAttendance==null?source.getExpectedAttendance():expectedAttendance);
        copy.setRequiredFacilities(requiredFacilities==null?source.getRequiredFacilities():requiredFacilities);
        copy.setAccessibilityNeeds(accessibilityNeeds==null?source.getAccessibilityNeeds():accessibilityNeeds);
        copy.setVenueRequirements(venueRequirements==null?source.getVenueRequirements():venueRequirements);
        return copy;
    }
}
