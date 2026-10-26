package com.example.connect_sphere.venue.service;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.connect_sphere.venue.dto.VenueOperatingHourDto;
import com.example.connect_sphere.venue.repository.VenueOperatingHourRepository;

/** The same structured schedule used by availability, presented consistently to every role. */
@Service
@Transactional(readOnly=true)
public class VenueHoursService {
    public record Schedule(List<VenueOperatingHourDto> hours, String summary) {}
    private final VenueOperatingHourRepository repository;
    public VenueHoursService(VenueOperatingHourRepository repository) { this.repository=repository; }
    public Schedule forVenue(UUID id) {
        var hours=repository.findByVenueIdOrderByDayOfWeekAscOpenTimeAsc(id).stream()
            .map(h -> new VenueOperatingHourDto(h.getDayOfWeek(),h.getOpenTime(),h.getCloseTime())).toList();
        return new Schedule(hours,summary(hours));
    }
    public static String summary(List<VenueOperatingHourDto> hours) {
        if (hours==null || hours.isEmpty()) return "Operating hours not configured";
        String[] days={"Monday","Tuesday","Wednesday","Thursday","Friday","Saturday","Sunday"};
        return hours.stream().sorted(Comparator.comparingInt(VenueOperatingHourDto::dayOfWeek))
            .map(h -> days[h.dayOfWeek()-1]+" "+time(h.openTime())+"–"+time(h.closeTime()))
            .collect(java.util.stream.Collectors.joining("; "));
    }
    private static String time(java.time.LocalTime value) {
        return value.getSecond()==0 && value.getNano()==0
            ? value.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")) : value.toString();
    }
}
