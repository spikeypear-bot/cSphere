package com.example.connect_sphere.venue.mapper;

import org.mapstruct.Mapper;

import com.example.connect_sphere.venue.dto.VenueDto;
import com.example.connect_sphere.venue.entity.Venue;

/** Maps stored layout/accessibility/facility labels to API enums; validation belongs in the service. */
@Mapper(componentModel = "spring")
public abstract class VenueMapper {
    @org.springframework.beans.factory.annotation.Autowired
    private com.example.connect_sphere.venue.service.VenueHoursService hours;

    @org.mapstruct.Mapping(target="operatingInformation", ignore=true)
    @org.mapstruct.Mapping(target="operatingHours", ignore=true)
    public abstract VenueDto toDto(Venue entity);

    @org.mapstruct.AfterMapping
    protected VenueDto withOperatingHours(Venue entity, @org.mapstruct.MappingTarget VenueDto mapped) {
        var schedule=hours==null
            ? new com.example.connect_sphere.venue.service.VenueHoursService.Schedule(java.util.List.of(), "Operating hours not configured")
            : hours.forVenue(entity.getVenueId());
        return new VenueDto(mapped.venueId(),mapped.venueAddress(),mapped.venueCapacity(),mapped.supportedLayouts(),
            schedule.summary(),mapped.additionalInformation(),mapped.venueAccessibilities(),mapped.venueFacilities(),
            schedule.hours(),mapped.setupMinutes(),mapped.turnaroundMinutes(),mapped.venueName(),
            mapped.latitude(),mapped.longitude());
    }
}
