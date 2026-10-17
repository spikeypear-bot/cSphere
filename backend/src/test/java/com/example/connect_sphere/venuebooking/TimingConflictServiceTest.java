package com.example.connect_sphere.venuebooking;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;
import com.example.connect_sphere.venue.repository.VenueRepository;
import com.example.connect_sphere.venueavailability.*;
import com.example.connect_sphere.venueavailability.AvailabilityModels.*;

@Tag("unit")
@Tag("VS-C4")
class TimingConflictServiceTest {
    final AvailabilityRepository repository=mock(AvailabilityRepository.class);
    final VenueRepository venues=mock(VenueRepository.class);
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    final TimingConflictService service=new TimingConflictService(repository,venues,jdbc);
    final UUID venue=UUID.randomUUID();
    Booking booking(String id, String start, String end, int setup, int turnaround) {
        var s=OffsetDateTime.parse(start+"+08:00"); var e=OffsetDateTime.parse(end+"+08:00");
        var occupied=EffectivePeriod.of(s,e,new Settings(setup,turnaround));
        return new Booking(UUID.fromString("00000000-0000-0000-0000-00000000000"+id), UUID.randomUUID(),null,"Event "+id,
            "approved",s,e,occupied.start(),occupied.end());
    }
    @ParameterizedTest(name="VS-C4 AC3, AC4 (boundary): setup={0}, turnaround={1}, expected={2}")
    @CsvSource({"0,0,0", "60,0,0", "61,0,1", "0,60,0", "0,61,1", "30,31,1", "30,30,0"})
    void comparesEffectivePeriodsUsingStrictBounds(int setup,int turnaround,int count) {
        when(venues.existsById(venue)).thenReturn(true);
        when(repository.bookings(venue)).thenReturn(List.of(
            booking("1","2030-04-01T12:00:00","2030-04-01T14:00:00",0,turnaround),
            booking("2","2030-04-01T15:00:00","2030-04-01T17:00:00",setup,0)));
        assertThat(service.list(venue)).hasSize(count);
        verifyNoInteractions(jdbc);
    }
    @Test void vsc4Ac3Ac4BoundaryCrossesMidnightAndReportsEveryPairOnce() {
        when(venues.existsById(venue)).thenReturn(true);
        var a=booking("1","2030-04-01T23:00:00","2030-04-01T23:50:00",0,30);
        var b=booking("2","2030-04-02T00:10:00","2030-04-02T01:00:00",30,0);
        var c=booking("3","2030-04-02T00:00:00","2030-04-02T00:15:00",0,0);
        when(repository.bookings(venue)).thenReturn(List.of(c,b,a));
        var pairs=service.list(venue);
        assertThat(pairs).hasSize(3);
        assertThat(pairs.getFirst().overlapStart()).isEqualTo(b.effectiveStart());
        assertThat(pairs.getFirst().overlapEnd()).isEqualTo(a.effectiveEnd());
        assertThat(pairs).allSatisfy(p -> assertThat(p.first().bookingId()).isNotEqualTo(p.second().bookingId()));
        verify(repository).bookings(venue);
        verifyNoInteractions(jdbc);
    }
    @Test void vsc4Ac5Ac10NegativeRetrievalReflectsStoredChangesWithoutWrites() {
        when(venues.existsById(venue)).thenReturn(true);
        var a=booking("1","2030-04-01T09:00:00","2030-04-01T11:00:00",0,0);
        var b=booking("2","2030-04-01T10:00:00","2030-04-01T12:00:00",0,0);
        when(repository.bookings(venue)).thenReturn(List.of(a,b),List.of(a));
        assertThat(service.list(venue)).hasSize(1);
        assertThat(service.list(venue)).isEmpty();
        verifyNoInteractions(jdbc);
    }
    @Test void vsc4Ac11FailurePropagatesInsteadOfReturningEmpty() {
        when(venues.existsById(venue)).thenReturn(true);
        when(repository.bookings(venue)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("Unavailable"));
        assertThatThrownBy(() -> service.list(venue)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        verifyNoInteractions(jdbc);
    }
    @Test void vsc4Ac6Ac11FailureApiReturns503RatherThanNoConflicts() throws Exception {
        var mockService=mock(TimingConflictService.class);
        when(mockService.list(venue)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("Unavailable"));
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new TimingConflictController(mockService)).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/venues/"+venue+"/timing-conflicts"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isServiceUnavailable())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message").value("Could not retrieve booking conflicts. Please try again."));
    }
}
