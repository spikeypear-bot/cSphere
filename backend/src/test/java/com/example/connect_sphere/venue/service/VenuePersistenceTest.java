package com.example.connect_sphere.venue.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import com.example.connect_sphere.common.enums.AccessibilityFeature;
import com.example.connect_sphere.common.enums.Facility;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.example.connect_sphere.venue.dto.CreateVenueDto;
import com.example.connect_sphere.venue.entity.VenueLayout;
import com.example.connect_sphere.venue.repository.VenueRepository;

import jakarta.persistence.EntityManager;

/** Real PostgreSQL round trip; the test transaction rolls back the venue. */
@SpringBootTest
@Transactional
class VenuePersistenceTest {
    @Autowired VenueService service;
    @Autowired VenueRepository repository;
    @Autowired EntityManager entityManager;

    @Test
    void persistsEveryEnumLabelAndCanClearSelections() {
        var features = java.util.Arrays.stream(AccessibilityFeature.values())
                .filter(value -> value != AccessibilityFeature.none).toList();
        var facilities = List.of(Facility.values());
        var saved = service.createVenue(new CreateVenueDto("Accessible venue", 100,
                List.of(VenueLayout.theatre), "Daily", null, features, facilities));
        entityManager.flush();
        entityManager.clear();
        var dto = service.get(saved.venueId());
        assertThat(dto.venueAccessibilities()).containsExactlyElementsOf(features);
        assertThat(dto.venueFacilities()).containsExactlyElementsOf(facilities);
        var types = (Object[]) entityManager.createNativeQuery(
                "SELECT pg_typeof(venue_accessibilities)::text, pg_typeof(venue_facilities)::text FROM venues WHERE venue_id = :id")
                .setParameter("id", saved.venueId()).getSingleResult();
        assertThat(types).containsExactly("accessibilities[]", "facilities[]");

        var venue = repository.findById(saved.venueId()).orElseThrow();
        venue.setVenueAccessibilities(new java.util.ArrayList<>(List.of("none")));
        venue.setVenueFacilities(new java.util.ArrayList<>());
        entityManager.flush();
        entityManager.clear();
        assertThat(service.get(saved.venueId()).venueAccessibilities()).containsExactly(AccessibilityFeature.none);
        assertThat(service.get(saved.venueId()).venueFacilities()).isEmpty();
        repository.findById(saved.venueId()).orElseThrow().setVenueAccessibilities(new java.util.ArrayList<>());
        entityManager.flush();
        entityManager.clear();
        assertThat(service.get(saved.venueId()).venueAccessibilities()).isEmpty();
        assertThat(service.get(saved.venueId()).venueCapacity()).isEqualTo(100);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void persistsLayoutsAndEmptyOptionalSelections(boolean omitted) {
        var saved = service.createVenue(new CreateVenueDto("Test venue", 50,
                List.of(VenueLayout.theatre, VenueLayout.classroom), "Mon-Fri 9-5", " Level 2 ",
                omitted ? null : List.of(), omitted ? null : List.of()));
        entityManager.flush();
        entityManager.clear();

        var reloaded = repository.findById(saved.venueId()).orElseThrow();
        assertThat(reloaded.getSupportedLayouts()).containsExactly("theatre", "classroom");
        assertThat(reloaded.getVenueCapacity()).isEqualTo(50);
        assertThat(reloaded.getAdditionalInformation()).isEqualTo("Level 2");
        assertThat(reloaded.getOperatingInformation()).isEqualTo("Mon-Fri 9-5");
        assertThat(service.get(saved.venueId()).venueAccessibilities()).isEmpty();
        assertThat(service.get(saved.venueId()).venueFacilities()).isEmpty();
        assertThat(service.list()).filteredOn(value -> value.venueId().equals(saved.venueId()))
                .singleElement().satisfies(value -> {
                    assertThat(value.venueAccessibilities()).isEmpty();
                    assertThat(value.venueFacilities()).isEmpty();
                });
        var defaults = (Object[]) entityManager.createNativeQuery(
                "SELECT cardinality(venue_accessibilities), cardinality(venue_facilities) FROM venues WHERE venue_id = :id")
                .setParameter("id", saved.venueId()).getSingleResult();
        assertThat(defaults).containsExactly(0, 0);
    }
}
