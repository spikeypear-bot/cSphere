package com.example.connect_sphere.venue.mapper;

import org.mapstruct.Mapper;

import com.example.connect_sphere.venue.dto.VenueDto;
import com.example.connect_sphere.venue.entity.Venue;

/** Read mapping; creation and validation belong in the future venue service. */
@Mapper(componentModel = "spring")
public interface VenueMapper {
    VenueDto toDto(Venue entity);
}
