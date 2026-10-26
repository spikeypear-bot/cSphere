package com.example.connect_sphere.venue.service;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.time.LocalTime;
import org.junit.jupiter.api.*;
import com.example.connect_sphere.venue.dto.VenueOperatingHourDto;
import com.example.connect_sphere.venue.entity.*;
import com.example.connect_sphere.venue.mapper.VenueMapper;
import com.example.connect_sphere.venue.repository.VenueOperatingHourRepository;

@Tag("unit") @Tag("VS17") @Tag("VS07") @Tag("EC03") @Tag("EO04")
class VenueHoursServiceTest {
 @Test @DisplayName("VS17 (happy): weekly hours are ordered and derived from the structured schedule")
 void ordered() {
  assertThat(VenueHoursService.summary(List.of(new VenueOperatingHourDto(5,LocalTime.of(10,0),LocalTime.of(17,0)),new VenueOperatingHourDto(1,LocalTime.of(9,0),LocalTime.of(18,0)))))
   .isEqualTo("Monday 09:00–18:00; Friday 10:00–17:00");
 }
 @Test @DisplayName("VS17 (boundary): missing schedules are explicitly unconfigured rather than advertised as open")
 void missing() {
  assertThat(VenueHoursService.summary(List.of())).isEqualTo("Operating hours not configured");
  assertThat(VenueHoursService.summary(null)).isEqualTo("Operating hours not configured");
 }
 @Test @DisplayName("VS17 (boundary): allowed seconds are not hidden by minute-only display rounding")
 void precision() {
  assertThat(VenueHoursService.summary(List.of(new VenueOperatingHourDto(1,LocalTime.of(9,0,30),LocalTime.of(18,0)))))
   .isEqualTo("Monday 09:00:30–18:00");
 }
 @Test @DisplayName("VS17 (cross-cutting): shared venue mapper ignores stale descriptive text and supplies actual weekly hours")
 void mapper() {
  var repo=mock(VenueOperatingHourRepository.class); var id=UUID.randomUUID();
  when(repo.findByVenueIdOrderByDayOfWeekAscOpenTimeAsc(id)).thenReturn(List.of(new VenueOperatingHour(id,1,LocalTime.of(9,0),LocalTime.of(18,0))));
  var mapper=org.mapstruct.factory.Mappers.getMapper(VenueMapper.class);
  org.springframework.test.util.ReflectionTestUtils.setField(mapper,"hours",new VenueHoursService(repo));
  var venue=new Venue(); venue.setVenueId(id); venue.setOperatingInformation("Incorrect legacy text: open all day");
  assertThat(mapper.toDto(venue).operatingInformation()).isEqualTo("Monday 09:00–18:00");
  assertThat(mapper.toDto(venue).operatingHours()).containsExactly(new VenueOperatingHourDto(1,LocalTime.of(9,0),LocalTime.of(18,0)));
  when(repo.findByVenueIdOrderByDayOfWeekAscOpenTimeAsc(id)).thenReturn(List.of());
  assertThat(mapper.toDto(venue).operatingInformation()).isEqualTo("Operating hours not configured");
 }
}
