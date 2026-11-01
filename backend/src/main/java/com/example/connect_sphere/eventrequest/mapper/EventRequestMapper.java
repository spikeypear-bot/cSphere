package com.example.connect_sphere.eventrequest.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.example.connect_sphere.common.status.JourneyStatusResolver;

import com.example.connect_sphere.eventrequest.dto.EventRequestDto;
import com.example.connect_sphere.eventrequest.entity.EventRequest;

/** Read-direction only — writes go through EventRequestService, which applies
 * fields onto the entity by hand so it can enforce the draft/submit rules
 * (see the mock package's convention note: this mirrors it for the direction
 * that's actually a straight 1:1 copy). */
@Mapper(componentModel = "spring", uses = { UserNameLookup.class, JourneyStatusResolver.class })
public interface EventRequestMapper {
    @Mapping(target = "createdByName", source = "createdBy", qualifiedByName = "username")
    @Mapping(target = "journeyStatus", source = ".", qualifiedByName = "journeyStatus")
    @Mapping(target = "registrationClosesAt",
            expression = "java(entity.getRegistrationClosesAt() != null ? entity.getRegistrationClosesAt() : entity.getStartDatetime())")
    EventRequestDto toDto(EventRequest entity);
}
