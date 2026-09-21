package com.example.connect_sphere.venue.dto;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.example.connect_sphere.common.enums.AccessibilityFeature;
import com.example.connect_sphere.common.enums.Facility;
import com.example.connect_sphere.venue.entity.VenueLayout;
import lombok.Getter;
import lombok.Setter;

/** Omitted fields retain saved values. Null is invalid; empty optional lists clear selections. */
@Getter
@Setter
public class UpdateVenueDto {
    private Integer venueCapacity;
    /** Avoid Jackson's default truncation of fractional numbers to integers. */
    @JsonSetter(value = "venueCapacity", nulls = Nulls.FAIL)
    public void setVenueCapacity(java.math.BigDecimal value) {
        this.venueCapacity = value.intValueExact();
    }

    @JsonSetter(nulls = Nulls.FAIL)
    private List<VenueLayout> supportedLayouts;
    @JsonSetter(nulls = Nulls.FAIL)
    private List<AccessibilityFeature> venueAccessibilities;
    @JsonSetter(nulls = Nulls.FAIL)
    private List<Facility> venueFacilities;
    @JsonSetter(nulls = Nulls.FAIL)
    private String operatingInformation;
    @JsonSetter(nulls = Nulls.FAIL)
    private String additionalInformation;
}
