package com.example.connect_sphere.changerequest;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.example.connect_sphere.activity.service.ActivityService;
import com.example.connect_sphere.common.enums.AccessibilityFeature;
import com.example.connect_sphere.common.enums.Facility;
import com.example.connect_sphere.eventrequest.dto.SaveEventRequestRequest;

/**
 * EO03/EC-NEW3: a request's changeable details as plain JSON values, and what
 * differs between two sets. "Changed" means changed in meaning, as the change
 * log judges it (EC-NEW5): the same moment in another offset, the same list in
 * another order, surrounding spaces, or 150 and 150.0 are all unchanged.
 */
final class ChangeFields {

    /** In the form's order. */
    static final Map<String, String> LABELS = labels();

    /** Week 4's important fields plus accessibility: a change to any of these
     * may invalidate venue, equipment or safety arrangements (EC08 re-planning). */
    static final Set<String> AFFECTS_ARRANGEMENTS = Set.of("startDatetime", "endDatetime", "expectedAttendance",
            "venueRequirements", "equipmentRequirements", "accessibilityNeeds", "requiredFacilities");

    private static final DateTimeFormatter SINGAPORE = DateTimeFormatter
            .ofPattern("d MMM yyyy, h:mm a", Locale.ENGLISH).withZone(ZoneId.of("Asia/Singapore"));

    private ChangeFields() {
    }

    private static Map<String, String> labels() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("eventName", "Event name");
        labels.put("purpose", "Purpose");
        labels.put("description", "Description");
        labels.put("startDatetime", "Start");
        labels.put("endDatetime", "End");
        labels.put("expectedAttendance", "Expected attendance");
        labels.put("venueRequirements", "Venue requirements");
        labels.put("equipmentRequirements", "Equipment requirements");
        labels.put("accessibilityNeeds", "Accessibility needs");
        labels.put("requiredFacilities", "Required facilities");
        labels.put("registrationNeeds", "Registration");
        labels.put("registrationCapacity", "Registration capacity");
        labels.put("registrationOpensAt", "Registration opens");
        labels.put("attendeeInstructions", "Attendee instructions");
        return labels;
    }

    /** Every changeable field, normalised: text trimmed (blank is null), times
     * as ISO instants with their offset, lists as names. */
    static Map<String, Object> valuesOf(SaveEventRequestRequest d) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("eventName", text(d.eventName()));
        v.put("purpose", text(d.purpose()));
        v.put("description", text(d.description()));
        v.put("startDatetime", d.startDatetime() == null ? null : d.startDatetime().toString());
        v.put("endDatetime", d.endDatetime() == null ? null : d.endDatetime().toString());
        v.put("expectedAttendance", d.expectedAttendance());
        v.put("venueRequirements", text(d.venueRequirements()));
        v.put("equipmentRequirements", text(d.equipmentRequirements()));
        v.put("accessibilityNeeds", d.accessibilityNeeds() == null ? List.of()
                : d.accessibilityNeeds().stream().map(Enum::name).toList());
        v.put("requiredFacilities", d.requiredFacilities() == null ? List.of()
                : d.requiredFacilities().stream().map(Enum::name).toList());
        v.put("registrationNeeds", d.registrationNeeds());
        v.put("registrationCapacity", d.registrationCapacity());
        v.put("registrationOpensAt", d.registrationOpensAt() == null ? null : d.registrationOpensAt().toString());
        v.put("attendeeInstructions", text(d.attendeeInstructions()));
        return v;
    }

    /** The details back from stored values (trimmed text, parsed times). */
    static SaveEventRequestRequest detailsOf(Map<String, Object> v) {
        return new SaveEventRequestRequest(
                (String) v.get("eventName"), (String) v.get("purpose"), (String) v.get("description"),
                time(v.get("startDatetime")), time(v.get("endDatetime")),
                v.get("expectedAttendance") == null ? null : ((Number) v.get("expectedAttendance")).intValue(),
                (String) v.get("venueRequirements"), (String) v.get("equipmentRequirements"),
                names(v.get("accessibilityNeeds")).stream().map(AccessibilityFeature::valueOf).toList(),
                names(v.get("requiredFacilities")).stream().map(Facility::valueOf).toList(),
                (Boolean) v.get("registrationNeeds"),
                v.get("registrationCapacity") == null ? null : ((Number) v.get("registrationCapacity")).intValue(),
                time(v.get("registrationOpensAt")), null, (String) v.get("attendeeInstructions"));
    }

    /** The fields whose meaning differs, in the form's order. */
    static List<ChangeRequestDto.FieldChange> changes(Map<String, Object> before, Map<String, Object> after) {
        List<ChangeRequestDto.FieldChange> changes = new ArrayList<>();
        LABELS.forEach((field, label) -> {
            if (!ActivityService.sameValue(before.get(field), after.get(field))) {
                changes.add(new ChangeRequestDto.FieldChange(field, label, before.get(field), after.get(field)));
            }
        });
        return changes;
    }

    /** The changed fields' new values, for the change log (EC-NEW5 judges Major from the keys). */
    static Map<String, Object> changedValues(List<ChangeRequestDto.FieldChange> changes) {
        Map<String, Object> values = new LinkedHashMap<>();
        changes.forEach(c -> values.put(c.field(), c.after()));
        return values;
    }

    /** "Expected attendance: 150 → 200; Start: …" as a person reads it, Singapore time. */
    static String describe(List<ChangeRequestDto.FieldChange> changes) {
        return String.join("; ", changes.stream()
                .map(c -> c.label() + ": " + show(c.field(), c.before()) + " → " + show(c.field(), c.after()))
                .toList());
    }

    static String show(String field, Object value) {
        if (value == null || value instanceof String s && s.isBlank()
                || value instanceof Collection<?> c && c.isEmpty()) {
            return "—";
        }
        if (field.endsWith("Datetime") || field.equals("registrationOpensAt")) {
            return SINGAPORE.format(OffsetDateTime.parse((String) value));
        }
        if (field.equals("accessibilityNeeds")) {
            return String.join(", ", names(value).stream().map(AccessibilityFeature::labelOf).toList());
        }
        if (field.equals("requiredFacilities")) {
            return String.join(", ", names(value).stream().map(Facility::labelOf).toList());
        }
        if (value instanceof Boolean b) {
            return b ? "Yes" : "No";
        }
        return value.toString();
    }

    private static String text(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static OffsetDateTime time(Object value) {
        return value == null ? null : OffsetDateTime.parse((String) value);
    }

    private static List<String> names(Object value) {
        return value instanceof Collection<?> c ? c.stream().map(Object::toString).toList() : List.of();
    }
}
