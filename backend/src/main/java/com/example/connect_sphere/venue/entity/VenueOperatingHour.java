package com.example.connect_sphere.venue.entity;

import java.time.LocalTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "venue_operating_hours")
@Getter
@Setter
public class VenueOperatingHour {
    @Id
    private UUID operatingHourId;
    @Column(name = "venue_id", nullable = false)
    private UUID venueId;
    @Column(name = "day_of_week", nullable = false)
    private int dayOfWeek;
    @Column(name = "open_time", nullable = false)
    private LocalTime openTime;
    @Column(name = "close_time", nullable = false)
    private LocalTime closeTime;

    public VenueOperatingHour() {
    }

    public VenueOperatingHour(UUID venueId, int dayOfWeek, LocalTime openTime, LocalTime closeTime) {
        this.operatingHourId = UUID.randomUUID();
        this.venueId = venueId;
        this.dayOfWeek = dayOfWeek;
        this.openTime = openTime;
        this.closeTime = closeTime;
    }
}
