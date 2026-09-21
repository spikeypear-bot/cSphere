package com.example.connect_sphere.venue.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import com.example.connect_sphere.common.enums.AccessibilityFeature;
import com.example.connect_sphere.common.enums.Facility;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mapstruct.factory.Mappers;

import com.example.connect_sphere.common.web.ApiExceptionHandler;
import com.example.connect_sphere.venue.dto.CreateVenueDto;
import com.example.connect_sphere.venue.entity.VenueLayout;
import com.example.connect_sphere.venue.mapper.VenueMapper;
import com.example.connect_sphere.venue.repository.VenueRepository;

class VenueServiceTest {
    private final VenueRepository repository = mock(VenueRepository.class);
    private final VenueService service = new VenueService(repository, Mappers.getMapper(VenueMapper.class));

    @Test
    void copiesSelectionsAndNormalisesMissingLists() {
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
        var result = service.createVenue(new CreateVenueDto("Venue", 50, List.of(VenueLayout.theatre),
                "Daily", null, List.of(AccessibilityFeature.none), List.of(Facility.stage)));
        assertThat(result.venueAccessibilities()).containsExactly(AccessibilityFeature.none);
        assertThat(result.venueFacilities()).containsExactly(Facility.stage);
        var empty = service.createVenue(input("Venue", 50, List.of(VenueLayout.theatre), "Daily"));
        assertThat(empty.venueAccessibilities()).isEmpty();
        assertThat(empty.venueFacilities()).isEmpty();
    }

    static Stream<CreateVenueDto> invalidSelections() {
        return Stream.of(
                selections(Arrays.asList(AccessibilityFeature.elevators, null), List.of()),
                selections(List.of(AccessibilityFeature.elevators, AccessibilityFeature.elevators), List.of()),
                selections(List.of(AccessibilityFeature.none, AccessibilityFeature.elevators), List.of()),
                selections(List.of(), Arrays.asList(Facility.stage, null)),
                selections(List.of(), List.of(Facility.stage, Facility.stage)));
    }

    private static CreateVenueDto selections(List<AccessibilityFeature> accessibility, List<Facility> facilities) {
        return new CreateVenueDto("Venue", 50, List.of(VenueLayout.theatre), "Daily", null,
                accessibility, facilities);
    }

    @ParameterizedTest
    @MethodSource("invalidSelections")
    void rejectsInvalidSelectionsBeforeSaving(CreateVenueDto input) {
        assertThatThrownBy(() -> service.createVenue(input)).isInstanceOf(InvalidVenueException.class);
        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 50, 50000})
    void createsVenueWithSharedCapacityAndTrimmedText(int capacity) {
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
        var result = service.createVenue(new CreateVenueDto("  #01-02 Example\nSingapore 123456  ",
                capacity, List.of(VenueLayout.theatre, VenueLayout.classroom), " Mon-Fri 9-5 ", null,
                List.of(AccessibilityFeature.step_free_access), List.of(Facility.projection)));
        assertThat(result.venueId()).isNotNull();
        assertThat(result.venueAddress()).isEqualTo("#01-02 Example\nSingapore 123456");
        assertThat(result.venueCapacity()).isEqualTo(capacity);
        assertThat(result.supportedLayouts()).containsExactly(VenueLayout.theatre, VenueLayout.classroom);
        assertThat(result.operatingInformation()).isEqualTo("Mon-Fri 9-5");
        assertThat(result.additionalInformation()).isNull();
        assertThat(result.venueAccessibilities()).containsExactly(AccessibilityFeature.step_free_access);
        assertThat(result.venueFacilities()).containsExactly(Facility.projection);
        verify(repository).save(any());
    }

    static Stream<CreateVenueDto> invalidInputs() {
        return Stream.of(null,
                new CreateVenueDto(null, null, null, null, null, null, null),
                input(" \t", 50, List.of(VenueLayout.theatre), "hours"),
                input("a".repeat(501), 50, List.of(VenueLayout.theatre), "hours"),
                input("address", 0, List.of(VenueLayout.theatre), "hours"),
                input("address", -1, List.of(VenueLayout.theatre), "hours"),
                input("address", 50001, List.of(VenueLayout.theatre), "hours"),
                input("address", 50, List.of(), "hours"),
                input("address", 50, Arrays.asList(VenueLayout.theatre, null), "hours"),
                input("address", 50, List.of(VenueLayout.theatre, VenueLayout.theatre), "hours"),
                input("address", 50, List.of(VenueLayout.theatre), " \n"));
    }

    private static CreateVenueDto input(String address, Integer capacity, List<VenueLayout> layouts, String hours) {
        return new CreateVenueDto(address, capacity, layouts, hours, null, null, null);
    }

    @ParameterizedTest
    @MethodSource("invalidInputs")
    void rejectsInvalidInputBeforeSaving(CreateVenueDto input) {
        assertThatThrownBy(() -> service.createVenue(input)).isInstanceOf(InvalidVenueException.class);
        verifyNoInteractions(repository);
    }

    static Stream<CreateVenueDto> invalidBasicFields() {
        return invalidInputs().filter(java.util.Objects::nonNull);
    }

    @ParameterizedTest
    @MethodSource("invalidBasicFields")
    void selectionsDoNotBypassBasicVenueValidation(CreateVenueDto input) {
        var withSelections = new CreateVenueDto(input.venueAddress(), input.venueCapacity(),
                input.supportedLayouts(), input.operatingInformation(), input.additionalInformation(),
                List.of(AccessibilityFeature.elevators), List.of(Facility.stage));
        assertThatThrownBy(() -> service.createVenue(withSelections)).isInstanceOf(InvalidVenueException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void accepts500CharacterAddressAfterTrimming() {
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
        assertThat(service.createVenue(input(" " + "a".repeat(500) + " ", 50,
                List.of(VenueLayout.banquet), "hours")).venueAddress()).hasSize(500);
    }

    @Test
    void returnsFieldMessagesInExistingApiErrorShape() {
        var exception = catchThrowableOfType(() -> service.createVenue(
                new CreateVenueDto(null, null, null, null, null, null, null)), InvalidVenueException.class);
        var response = new ApiExceptionHandler().handleInvalidVenue(exception);
        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(response.getBody().message()).contains("venueAddress", "venueCapacity",
                "supportedLayouts", "operatingInformation");
    }
}
