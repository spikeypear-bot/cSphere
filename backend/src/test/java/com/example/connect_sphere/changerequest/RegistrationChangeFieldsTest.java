package com.example.connect_sphere.changerequest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import com.example.connect_sphere.common.enums.AccessibilityFeature;
import com.example.connect_sphere.eventrequest.dto.SaveEventRequestRequest;

@Tag("unit")
@Tag("AT01")
@Tag("EO03")
class RegistrationChangeFieldsTest {
    @Test
    @DisplayName("AT01 EO03 (cross-cutting): storing proposed changes preserves registration settings and derives closing from start")
    void preservesRegistrationSettings() {
        OffsetDateTime start = OffsetDateTime.parse("2027-03-10T09:00:00+08:00");
        OffsetDateTime opens = start.minusDays(30);
        SaveEventRequestRequest details = new SaveEventRequestRequest("Workshop", "Learning", null,
                start, start.plusHours(2), 10, "Room", null, List.of(AccessibilityFeature.none), List.of(),
                true, 10, opens, start.minusDays(1), "Bring your ticket");
        var saved = ChangeFields.valuesOf(details);
        var restored = ChangeFields.detailsOf(saved);
        assertThat(restored.registrationCapacity()).isEqualTo(10);
        assertThat(restored.registrationOpensAt()).isEqualTo(opens);
        assertThat(restored.attendeeInstructions()).isEqualTo("Bring your ticket");
        assertThat(restored.registrationClosesAt()).isEqualTo(start);
        var changed = new java.util.LinkedHashMap<>(saved);
        changed.put("registrationCapacity", 12);
        assertThat(ChangeFields.changes(saved, changed)).singleElement()
                .satisfies(c -> assertThat(c.field()).isEqualTo("registrationCapacity"));
    }
}
