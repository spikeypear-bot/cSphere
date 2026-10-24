package com.example.connect_sphere.changerequest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.example.connect_sphere.common.enums.AccessibilityFeature;
import com.example.connect_sphere.common.enums.Facility;
import com.example.connect_sphere.eventrequest.dto.SaveEventRequestRequest;

/** EO03: "what changed" judged by meaning, and shown as a person reads it. */
@Tag("unit")
@Tag("EO03")
@Tag("EC-NEW3")
class ChangeFieldsTest {

    private static SaveEventRequestRequest details(String name, String start, int attendance,
            List<AccessibilityFeature> accessibility) {
        return new SaveEventRequestRequest(name, "Update", null, OffsetDateTime.parse(start),
                OffsetDateTime.parse("2027-03-10T12:00:00+08:00"), attendance, "Theatre", null, accessibility,
                List.of(Facility.values()[0]), false);
    }

    private static final SaveEventRequestRequest ORIGINAL = details("Town Hall", "2027-03-10T09:00:00+08:00", 150,
            List.of(AccessibilityFeature.step_free_access, AccessibilityFeature.elevators));

    @Test
    @DisplayName("EO03 AC20, AC21 (boundary): the same moment in another offset, the same list in another order, surrounding spaces and 150.0 are not changes")
    void sameMeaningIsNoChange() {
        Map<String, Object> before = ChangeFields.valuesOf(ORIGINAL);
        Map<String, Object> after = ChangeFields.valuesOf(details("  Town Hall ", "2027-03-10T01:00:00Z", 150,
                List.of(AccessibilityFeature.elevators, AccessibilityFeature.step_free_access)));
        after.put("expectedAttendance", 150.0);

        assertThat(ChangeFields.changes(before, after)).isEmpty();
    }

    @Test
    @DisplayName("EO03 AC9, AC22 (happy): each changed field is listed once, in the form's order, with its value before and after")
    void changesInFormOrder() {
        Map<String, Object> after = ChangeFields.valuesOf(details("Town Hall 2027", "2027-03-10T10:00:00+08:00", 200,
                List.of(AccessibilityFeature.step_free_access)));

        assertThat(ChangeFields.changes(ChangeFields.valuesOf(ORIGINAL), after))
                .extracting(ChangeRequestDto.FieldChange::field)
                .containsExactly("eventName", "startDatetime", "expectedAttendance", "accessibilityNeeds");
    }

    @Test
    @DisplayName("EO03 AC17, AC22 (happy): a change reads in Singapore time with human labels")
    void describedForPeople() {
        Map<String, Object> after = ChangeFields.valuesOf(details("Town Hall", "2027-03-10T10:30:00+08:00", 150,
                List.of(AccessibilityFeature.step_free_access)));

        assertThat(ChangeFields.describe(ChangeFields.changes(ChangeFields.valuesOf(ORIGINAL), after)))
                .isEqualTo("Start: 10 Mar 2027, 9:00 AM → 10 Mar 2027, 10:30 AM; "
                        + "Accessibility needs: Step-free access, Elevators → Step-free access");
    }

    @Test
    @DisplayName("EC-NEW3 AC31 (happy): stored values turn back into exactly the proposed details")
    void storedValuesRoundTrip() {
        SaveEventRequestRequest back = ChangeFields.detailsOf(ChangeFields.valuesOf(ORIGINAL));

        assertThat(back.startDatetime().toInstant()).isEqualTo(ORIGINAL.startDatetime().toInstant());
        assertThat(back.accessibilityNeeds()).isEqualTo(ORIGINAL.accessibilityNeeds());
        assertThat(back.requiredFacilities()).isEqualTo(ORIGINAL.requiredFacilities());
        assertThat(back.expectedAttendance()).isEqualTo(150);
    }
}
