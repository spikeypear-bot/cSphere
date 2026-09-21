package com.example.connect_sphere.venue.entity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Venue catalogue information. IDs are assigned by the application.
 * Enum labels use string arrays with explicit write casts to retain PostgreSQL
 * enum-array validation without relying on Hibernate named-enum-array binding.
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

    @JdbcTypeCode(SqlTypes.ARRAY)
    @ColumnTransformer(write = "cast(? as accessibilities[])")
    @Column(name = "venue_accessibilities", nullable = false, columnDefinition = "accessibilities[]")
    private List<String> venueAccessibilities = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.ARRAY)
    @ColumnTransformer(write = "cast(? as facilities[])")
    @Column(name = "venue_facilities", nullable = false, columnDefinition = "facilities[]")
    private List<String> venueFacilities = new ArrayList<>();

    @Column(name = "operating_information", nullable = false, columnDefinition = "text")
    private String operatingInformation;

    @Column(name = "additional_information", columnDefinition = "text")
    private String additionalInformation;

    public Venue() {
    }
}
