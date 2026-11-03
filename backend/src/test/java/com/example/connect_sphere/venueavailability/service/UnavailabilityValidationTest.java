package com.example.connect_sphere.venueavailability.service;

import static org.assertj.core.api.Assertions.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import com.example.connect_sphere.venueavailability.dto.AvailabilityModels.Request;

@Tag("unit")
@Tag("VS01")
class UnavailabilityValidationTest {
    private static final LocalDate TODAY = LocalDate.of(2026,10,7);

    @ParameterizedTest(name="scenario {0}: accepted={3}")
    @CsvSource(value={
        "1,2026-10-08T10:00,2026-10-09T11:00,true",
        "2,NULL,2026-10-09T11:00,false",
        "3,2026-10-08T10:00,NULL,false",
        "4,NULL,NULL,false",
        "5,2026-10-08T10:00,2026-10-08T10:00,false",
        "6,2026-10-09T10:00,2026-10-08T11:00,false",
        "7,2026-10-08T10:00,2026-10-08T11:00,true",
        "8,2026-10-08T10:00,2026-10-08T09:00,false",
        "9,2026-10-08T10:00,2026-10-08T10:00,false",
        "10,2026-10-07T10:30,2026-10-07T11:00,true",
        "11,2026-10-06T10:00,2026-10-08T11:00,false",
        "12,2026-10-05T10:00,2026-10-06T11:00,false",
        "13,2026-10-08T10:00,2026-10-09T11:00,true",
        "14,2026-10-31T23:00,2026-11-01T01:00,true",
        "15,2026-12-31T23:00,2027-01-01T01:00,true",
        "16,2027-02-28T23:00,2027-03-01T01:00,true",
        "17,2028-02-29T10:00,2028-03-01T11:00,true",
        "17,2028-02-28T10:00,2028-02-29T11:00,true",
        "18,2027-02-29T10:00,2027-03-01T11:00,false",
        "18,2027-02-28T10:00,2027-02-29T11:00,false",
        "19,2027-04-31T10:00,2027-05-01T11:00,false",
        "20,2027-04-30T10:00,2027-04-31T11:00,false"
    }, nullValues="NULL")
    void scenarios(int id, String start, String end, boolean accepted) {
        var error = catchThrowable(() -> AvailabilityService.validate(Request.fromJson(
                start == null ? null : start + "+08:00", end == null ? null : end + "+08:00", "Maintenance", null, null), TODAY));
        if (accepted) assertThat(error).isNull(); else assertThat(error).isNotNull();
    }

    @Test
    @DisplayName("VS01 (boundary): whether a date is in the past follows the Singapore calendar, not the offset supplied")
    void pastDateUsesSingaporeCalendarRatherThanSuppliedOffset() {
        var start = OffsetDateTime.parse("2026-10-06T16:00:00Z");
        assertThatCode(() -> AvailabilityService.validate(new Request(start,start.plusHours(1),"Maintenance",null,null),TODAY)).doesNotThrowAnyException();
        assertThatThrownBy(() -> AvailabilityService.validate(new Request(start.minusSeconds(1),start.plusHours(1),"Maintenance",null,null),TODAY))
                .hasMessageContaining("before today");
    }

    @Test
    @DisplayName("VS01 (negative): a period needs its times and a reason of 1 to 2000 characters")
    void aPeriodNeedsTimesAndAReason() {
        var start = OffsetDateTime.parse("2026-10-08T10:00:00+08:00");
        assertThatThrownBy(() -> AvailabilityService.validate(null, TODAY))
                .hasMessage("Start and end date/time are required.");
        for (String reason : java.util.Arrays.asList(null, "  ", "x".repeat(2001)))
            assertThatThrownBy(() -> AvailabilityService.validate(new Request(start, start.plusHours(1), reason, null, null), TODAY))
                    .hasMessage("Reason is required and must be at most 2000 characters.");
        assertThatCode(() -> AvailabilityService.validate(new Request(start, start.plusHours(1), "x".repeat(2000), null, null), TODAY))
                .doesNotThrowAnyException();
    }
}
