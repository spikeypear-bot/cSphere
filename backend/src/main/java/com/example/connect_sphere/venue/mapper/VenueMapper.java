package com.example.connect_sphere.venue.mapper;

import org.mapstruct.Mapper;

import com.example.connect_sphere.venue.dto.VenueDto;
import com.example.connect_sphere.venue.entity.Venue;

/** Maps stored layout/accessibility/facility labels to API enums; validation belongs in the service. */
@Mapper(componentModel = "spring")
public interface VenueMapper {
    VenueDto toDto(Venue entity);
}
