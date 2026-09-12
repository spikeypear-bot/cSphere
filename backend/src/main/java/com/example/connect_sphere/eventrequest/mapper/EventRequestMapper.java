package com.example.connect_sphere.eventrequest.mapper;

import org.mapstruct.Mapper;

import com.example.connect_sphere.eventrequest.dto.EventRequestDto;
import com.example.connect_sphere.eventrequest.entity.EventRequest;

/** Read-direction only — writes go through EventRequestService, which applies
 * fields onto the entity by hand so it can enforce the draft/submit rules
 * (see the mock package's convention note: this mirrors it for the direction
 * that's actually a straight 1:1 copy). */
@Mapper(componentModel = "spring")
public interface EventRequestMapper {
    EventRequestDto toDto(EventRequest entity);
}
