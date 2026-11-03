package com.example.connect_sphere.equipment.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// The period checks every reservation rule uses, against the window 10:00-13:00.
@Tag("unit")
@Tag("TS02")
@Tag("TS06")
@Tag("TS09")
class EquipmentLogTest {

    private static final Instant START = Instant.parse("2027-03-10T10:00:00Z");
    private static final Instant END = Instant.parse("2027-03-10T13:00:00Z");

    @ParameterizedTest(name = "{0}-{1}: overlaps {2}, covers {3}")
    @CsvSource({
            "07:00, 09:00, false, false",   // wholly before
            "07:00, 10:00, false, false",   // ends as the window starts
            "08:00, 11:00, true,  false",   // overlaps the start
            "11:00, 12:00, true,  false",   // inside, but not all of it
            "12:00, 15:00, true,  false",   // overlaps the end
            "13:00, 15:00, false, false",   // starts as the window ends
            "14:00, 16:00, false, false",   // wholly after
            "10:00, 13:00, true,  true",    // exactly the window
            "09:00, 14:00, true,  true",    // more than the window
    })
    @DisplayName("TS02 TS06 (boundary): a reservation overlaps a period when any part of it is shared, and covers it only when it holds all of it; touching ends do not overlap")
    void overlapsAndCovers(String from, String until, boolean overlaps, boolean covers) {
        EquipmentLog log = new EquipmentLog(UUID.randomUUID(), UUID.randomUUID(), 1, null,
                Instant.parse("2027-03-10T" + from + ":00Z"), Instant.parse("2027-03-10T" + until + ":00Z"));

        assertThat(log.overlaps(START, END)).isEqualTo(overlaps);
        assertThat(log.covers(START, END)).isEqualTo(covers);
    }

    @Test
    @DisplayName("TS09 (boundary): a reservation can be reduced by 0 up to all of its units, never by a negative number or more than it holds")
    void reduceStaysWithinTheReservation() {
        EquipmentLog log = new EquipmentLog(UUID.randomUUID(), UUID.randomUUID(), 3, null, START, END);
        assertThatThrownBy(() -> log.reduceBy(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> log.reduceBy(4)).isInstanceOf(IllegalArgumentException.class);
        assertThat(log.getQuantity()).isEqualTo(3);
        log.reduceBy(0);
        log.reduceBy(3);
        assertThat(log.getQuantity()).isZero();
    }
}
