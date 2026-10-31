package com.example.connect_sphere.venuebooking;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.example.connect_sphere.changerequest.*;
import com.example.connect_sphere.venueavailability.*;
import com.example.connect_sphere.venuebooking.hold.*;
import com.example.connect_sphere.venuebooking.service.*;

/** Controller contracts with faulted collaborators; real rollback/RBAC are integration-tested separately. */
@Tag("DEV16") @Tag("VS17") @Tag("VS-C1") @Tag("VS-C3") @Tag("VS-C4") @Tag("unit")
class VenueStaffFailureResponseTest {
    private RuntimeException unavailable(boolean transaction) {
        return transaction ? new CannotCreateTransactionException("private database connection details")
                : new DataAccessResourceFailureException("private SQL and credentials");
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    @DisplayName("DEV16 (failure): assessment storage failures return a retryable message without database details")
    void assessment(boolean transaction) throws Exception {
        UUID id=UUID.randomUUID();
        var service=mock(BookingAssessmentService.class);
        when(service.view(id)).thenThrow(unavailable(transaction));
        var mvc=MockMvcBuilders.standaloneSetup(new BookingAssessmentController(service)).build();
        mvc.perform(get("/api/venue-bookings/"+id+"/assessment"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("The assessment could not be saved or loaded. Please retry."));
        verify(service).view(id); verifyNoMoreInteractions(service);
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    @DisplayName("DEV16 (failure): review storage failures return a retryable message without database details")
    void reviews(boolean transaction) throws Exception {
        UUID id=UUID.randomUUID();
        var service=mock(VenueRequirementReviewService.class);
        when(service.forBooking(id)).thenThrow(unavailable(transaction));
        var mvc=MockMvcBuilders.standaloneSetup(new VenueRequirementReviewController(service)).build();
        mvc.perform(get("/api/venue-bookings/"+id+"/requirement-reviews"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("The requirement review could not be saved or loaded. Refresh and try again."));
        verify(service).forBooking(id); verifyNoMoreInteractions(service);
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    @DisplayName("DEV16 (failure): hold storage failures tell staff to refresh before making another decision")
    void holds(boolean transaction) throws Exception {
        UUID id=UUID.randomUUID();
        var service=mock(VenueHoldService.class);
        when(service.history(id)).thenThrow(unavailable(transaction));
        var mvc=MockMvcBuilders.standaloneSetup(new VenueHoldController(service,mock(VenueHoldRepository.class),mock(VenueBookingService.class))).build();
        mvc.perform(get("/api/venue-bookings/"+id+"/holds"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("The hold could not be updated. Refresh its details and try again."));
        verify(service).history(id); verifyNoMoreInteractions(service);
    }

    @Test
    @DisplayName("DEV16 (conflict): a failed hold conversion returns the conflict contract rather than success")
    void holdConversionConflict() throws Exception {
        UUID hold=UUID.randomUUID(), booking=UUID.randomUUID();
        var holds=mock(VenueHoldRepository.class);
        var bookings=mock(VenueBookingService.class);
        when(holds.bookingId(hold)).thenReturn(booking);
        when(bookings.approve(booking,hold)).thenThrow(new VenueBookingConflictException(List.of()));
        var mvc=MockMvcBuilders.standaloneSetup(new VenueHoldController(mock(VenueHoldService.class),holds,bookings)).build();
        mvc.perform(patch("/api/venue-holds/"+hold+"/convert"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This booking overlaps an existing approved booking for the selected venue."))
                .andExpect(jsonPath("$.conflicts").isEmpty())
                .andExpect(jsonPath("$.unavailablePeriods").isEmpty());
        verify(bookings).approve(booking,hold); verifyNoMoreInteractions(bookings);
    }

    @Test
    @DisplayName("DEV16 (negative): malformed unavailable-period JSON returns staff-facing validation before the service runs")
    void malformedUnavailability() throws Exception {
        var service=mock(AvailabilityService.class);
        var mvc=MockMvcBuilders.standaloneSetup(new AvailabilityController(service)).build();
        mvc.perform(post("/api/venues/"+UUID.randomUUID()+"/unavailability/preview")
                .contentType("application/json").content("{"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("Enter valid calendar dates and times with timezone offsets, and valid request fields."));
        verifyNoInteractions(service);
    }
}
