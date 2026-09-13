package com.example.connect_sphere.venue.entity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Basic venue catalogue information for VS06A, mapped to the V5 schema.
 * IDs are assigned by the application before persistence. Accessibility and
 * facilities are not mapped in this slice, so inserts use their database defaults
 * and updates leave any existing values intact.
 */
@Entity
@Table(name = "venues")
@Getter
@Setter
public class Venue {

    @Id
    @Column(name = "venue_id", nullable = false)
    private UUID venueId;

    @Column(name = "venue_address", nullable = false, columnDefinition = "text")
    private String venueAddress;

    @Column(name = "venue_capacity", nullable = false)
    private Integer venueCapacity;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "supported_layouts", nullable = false, columnDefinition = "text[]")
    private List<String> supportedLayouts = new ArrayList<>();

    @Column(name = "operating_information", nullable = false, columnDefinition = "text")
    private String operatingInformation;

    @Column(name = "additional_information", columnDefinition = "text")
    private String additionalInformation;

    public Venue() {
    }
}
