package com.example.connect_sphere.venue.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
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
    void persistsLayoutsAndUsesDefaultsForUnmappedCatalogueFields() {
        var saved = service.createVenue(new CreateVenueDto("Test venue", 50,
                List.of(VenueLayout.theatre, VenueLayout.classroom), "Mon-Fri 9-5", " Level 2 "));
        entityManager.flush();
        entityManager.clear();

        var reloaded = repository.findById(saved.venueId()).orElseThrow();
        assertThat(reloaded.getSupportedLayouts()).containsExactly("theatre", "classroom");
        assertThat(reloaded.getVenueCapacity()).isEqualTo(50);
        assertThat(reloaded.getAdditionalInformation()).isEqualTo("Level 2");
        var defaults = (Object[]) entityManager.createNativeQuery(
                "SELECT cardinality(venue_accessibilities), cardinality(venue_facilities) FROM venues WHERE venue_id = :id")
                .setParameter("id", saved.venueId()).getSingleResult();
        assertThat(defaults).containsExactly(0, 0);
    }
}
