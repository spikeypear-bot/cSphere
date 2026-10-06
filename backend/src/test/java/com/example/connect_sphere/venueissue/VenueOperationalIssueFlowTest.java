package com.example.connect_sphere.venueissue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.example.connect_sphere.testsupport.FlowSupport;
import com.example.connect_sphere.user.repository.UserRepository;
import com.example.connect_sphere.venue.dto.CreateVenueDto;
import com.example.connect_sphere.venue.entity.VenueLayout;
import com.example.connect_sphere.venue.service.VenueService;

import jakarta.persistence.EntityManager;

@Tag("integration")
@Tag("VS13")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class VenueOperationalIssueFlowTest {

    private static final String EVENT_START = "2027-03-10T01:00:00Z";
    private static final String EVENT_END = "2027-03-10T04:00:00Z";
    private static final String LATER = "2027-03-10T06:00:00Z";

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired VenueService venueService;
    @Autowired EntityManager entityManager;

    private FlowSupport flow;

    @BeforeEach
    void setUp() {
        flow = new FlowSupport(mvc, users);
    }

    private UUID venue() {
        UUID id = venueService.createVenue(new CreateVenueDto("VS13 Hall " + UUID.randomUUID(), 200,
                List.of(VenueLayout.theatre), "08:00-22:00 daily", null, List.of(), List.of())).venueId();
        entityManager.flush();
        return id;
    }

    private UUID eventManagedByEc1() throws Exception {
        UUID eventId = flow.approvedEvent("eo1", "ec1", FlowSupport.requestBody("Town Hall", 150, "none"));
        sync();
        return eventId;
    }

    private UUID booking(UUID venueId, UUID eventId, String bookingStatus) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO venue_bookings (booking_id, venue_id, event_id, status, booking_notes)
                VALUES (?, ?, ?, ?::venue_booking_status, 'Stage needed')
                """, id, venueId, eventId, bookingStatus);
        return id;
    }

    private static String issue(String description, String from, String until) {
        return "{\"description\":%s,\"affectedFrom\":%s,\"affectedUntil\":%s}".formatted(
                quoted(description), quoted(from), quoted(until));
    }

    private static String quoted(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    private ResultActions report(UUID venueId, String as, String body) throws Exception {
        return mvc.perform(post("/api/venues/" + venueId + "/operational-issues").with(flow.as(as))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private UUID reported(UUID venueId, String description, String from, String until) throws Exception {
        String response = report(venueId, "vs1", issue(description, from, until))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        sync();
        return UUID.fromString(flow.read(response).get("issueId").asString());
    }

    private ResultActions coordinatorIssues(String as) throws Exception {
        return mvc.perform(get("/api/coordinator/operational-issues").with(flow.as(as)));
    }

    private static String entry(UUID issueId) {
        return "$[?(@.issueId == '" + issueId + "')]";
    }

    private void sync() {
        entityManager.flush();
        entityManager.clear();
    }

    private int issuesFor(UUID venueId) {
        sync();
        return jdbc.queryForObject(
                "SELECT count(*) FROM venue_operational_issues WHERE venue_id = ?", Integer.class, venueId);
    }

    private String bookingsSnapshot(UUID venueId) {
        sync();
        return jdbc.queryForObject("""
                SELECT coalesce(string_agg(to_jsonb(b)::text, ',' ORDER BY b.booking_id), '')
                FROM venue_bookings b WHERE b.venue_id = ?
                """, String.class, venueId);
    }

    @Test
    void venueStaffReportsAnIssueWithAPeriodAndItIsSavedAgainstTheVenue() throws Exception {
        UUID venueId = venue();

        report(venueId, "vs1", issue("Air conditioning failure", EVENT_START, EVENT_END))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.issueId").isNotEmpty())
                .andExpect(jsonPath("$.venueId").value(venueId.toString()))
                .andExpect(jsonPath("$.description").value("Air conditioning failure"))
                .andExpect(jsonPath("$.affectedFrom").value(EVENT_START))
                .andExpect(jsonPath("$.affectedUntil").value(EVENT_END))
                .andExpect(jsonPath("$.createdBy").value(flow.idOf("vs1").toString()));

        assertThat(issuesFor(venueId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT description FROM venue_operational_issues WHERE venue_id = ?", String.class, venueId))
                .isEqualTo("Air conditioning failure");
    }

    @Test
    void aNewlyReportedIssueComesBackWithTheTimeItWasReported() throws Exception {
        UUID venueId = venue();

        report(venueId, "vs1", issue("Air conditioning failure", EVENT_START, EVENT_END))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdAt").isNotEmpty());
    }

    @Test
    void anIssueCanBeReportedWithoutAnAffectedPeriod() throws Exception {
        UUID venueId = venue();

        report(venueId, "vs1", issue("Lift under inspection", null, null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.description").value("Lift under inspection"))
                .andExpect(jsonPath("$.affectedFrom").isEmpty())
                .andExpect(jsonPath("$.affectedUntil").isEmpty());

        assertThat(issuesFor(venueId)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"affectedFrom\":null,\"affectedUntil\":null}",
            "{\"description\":\"\",\"affectedFrom\":null,\"affectedUntil\":null}",
            "{\"description\":\"   \",\"affectedFrom\":null,\"affectedUntil\":null}"})
    void anIssueWithoutADescriptionIsRefusedWithAMessageAndNothingIsSaved(String body) throws Exception {
        UUID venueId = venue();

        report(venueId, "vs1", body)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("Issue description is required."));

        assertThat(issuesFor(venueId)).isZero();
    }

    @Test
    void anEndEarlierThanTheStartIsRefusedWithAMessageAndNothingIsSaved() throws Exception {
        UUID venueId = venue();

        report(venueId, "vs1", issue("Air conditioning failure", EVENT_END, EVENT_START))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("Affected end date/time must be after the start date/time."));

        assertThat(issuesFor(venueId)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"start", "end"})
    void aPeriodWithOnlyOneOfStartAndEndIsRefusedWithAMessageAndNothingIsSaved(String supplied) throws Exception {
        UUID venueId = venue();
        String body = supplied.equals("start")
                ? issue("Air conditioning failure", EVENT_START, null)
                : issue("Air conditioning failure", null, EVENT_END);

        report(venueId, "vs1", body)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("Affected start and end date/time must be supplied together."));

        assertThat(issuesFor(venueId)).isZero();
    }

    @Test
    void reportingAnIssueDoesNotChangeCancelOrRemoveTheVenuesBookings() throws Exception {
        UUID venueId = venue();
        UUID eventId = eventManagedByEc1();
        UUID approved = booking(venueId, eventId, "approved");
        UUID pending = booking(venueId, eventId, "pending");
        String before = bookingsSnapshot(venueId);

        reported(venueId, "Air conditioning failure", EVENT_START, EVENT_END);

        assertThat(bookingsSnapshot(venueId)).isEqualTo(before);
        assertThat(jdbc.queryForObject(
                "SELECT status::text FROM venue_bookings WHERE booking_id = ?", String.class, approved))
                .isEqualTo("approved");
        assertThat(jdbc.queryForObject(
                "SELECT status::text FROM venue_bookings WHERE booking_id = ?", String.class, pending))
                .isEqualTo("pending");
    }

    @Test
    void venueStaffCanViewAReportedIssueUnderItsVenueOnly() throws Exception {
        UUID venueId = venue();
        UUID otherVenueId = venue();
        UUID issueId = reported(venueId, "Air conditioning failure", EVENT_START, EVENT_END);

        mvc.perform(get("/api/venues/" + venueId + "/operational-issues").with(flow.as("vs2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].issueId").value(issueId.toString()))
                .andExpect(jsonPath("$[0].venueId").value(venueId.toString()))
                .andExpect(jsonPath("$[0].description").value("Air conditioning failure"))
                .andExpect(jsonPath("$[0].affectedFrom").value(EVENT_START))
                .andExpect(jsonPath("$[0].affectedUntil").value(EVENT_END));
        mvc.perform(get("/api/venues/" + otherVenueId + "/operational-issues").with(flow.as("vs1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void theCoordinatorSeesAnIssueAtAVenueTheyManageWithTheEventItOverlaps() throws Exception {
        UUID venueId = venue();
        UUID eventId = eventManagedByEc1();
        booking(venueId, eventId, "approved");
        UUID issueId = reported(venueId, "Air conditioning failure", "2027-03-10T03:59:00Z", LATER);

        coordinatorIssues("ec1")
                .andExpect(status().isOk())
                .andExpect(jsonPath(entry(issueId) + ".venueId", contains(venueId.toString())))
                .andExpect(jsonPath(entry(issueId) + ".description", contains("Air conditioning failure")))
                .andExpect(jsonPath(entry(issueId) + ".affectedFrom", contains("2027-03-10T03:59:00Z")))
                .andExpect(jsonPath(entry(issueId) + ".affectedUntil", contains(LATER)))
                .andExpect(jsonPath(entry(issueId) + ".overlappingEvents[*].eventId", contains(eventId.toString())))
                .andExpect(jsonPath(entry(issueId) + ".overlappingEvents[*].eventName", contains("Town Hall")));
    }

    @Test
    void anIssueThatStartsWhenTheEventEndsIsListedWithoutAnOverlappingEvent() throws Exception {
        UUID venueId = venue();
        booking(venueId, eventManagedByEc1(), "approved");
        UUID issueId = reported(venueId, "Floor polishing", EVENT_END, LATER);

        coordinatorIssues("ec1")
                .andExpect(jsonPath(entry(issueId) + ".description", contains("Floor polishing")))
                .andExpect(jsonPath(entry(issueId) + ".overlappingEvents[*]", empty()));
    }

    @Test
    void anIssueWithoutAPeriodIsListedForTheCoordinatorWithoutOverlappingEvents() throws Exception {
        UUID venueId = venue();
        booking(venueId, eventManagedByEc1(), "approved");
        UUID issueId = reported(venueId, "Lift under inspection", null, null);

        coordinatorIssues("ec1")
                .andExpect(jsonPath(entry(issueId) + ".description", contains("Lift under inspection")))
                .andExpect(jsonPath(entry(issueId) + ".overlappingEvents[*]", empty()));
    }

    @Test
    void aCoordinatorDoesNotSeeIssuesAtVenuesTheyDoNotManage() throws Exception {
        UUID venueId = venue();
        booking(venueId, eventManagedByEc1(), "approved");
        UUID issueId = reported(venueId, "Air conditioning failure", EVENT_START, EVENT_END);

        coordinatorIssues("ec2")
                .andExpect(status().isOk())
                .andExpect(jsonPath(entry(issueId), empty()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"eo1", "ec1", "ts1", "att1"})
    void otherRolesCannotReportOrListVenueIssuesAndNothingIsSaved(String username) throws Exception {
        UUID venueId = venue();

        report(venueId, username, issue("Air conditioning failure", EVENT_START, EVENT_END))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/venues/" + venueId + "/operational-issues").with(flow.as(username)))
                .andExpect(status().isForbidden());

        assertThat(issuesFor(venueId)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"eo1", "vs1", "ts1", "att1"})
    void onlyCoordinatorsCanOpenTheCoordinatorIssueList(String username) throws Exception {
        coordinatorIssues(username).andExpect(status().isForbidden());
    }

    @Test
    void anUnauthenticatedCallerCannotReportAnIssueAndNothingIsSaved() throws Exception {
        UUID venueId = venue();

        mvc.perform(post("/api/venues/" + venueId + "/operational-issues")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(issue("Air conditioning failure", EVENT_START, EVENT_END)))
                .andExpect(status().isUnauthorized());

        assertThat(issuesFor(venueId)).isZero();
    }

    @Test
    void aVenueThatDoesNotExistReturnsNotFound() throws Exception {
        report(UUID.randomUUID(), "vs1", issue("Air conditioning failure", EVENT_START, EVENT_END))
                .andExpect(status().isNotFound());
    }

    @Test
    void aMalformedVenueIdReturnsBadRequest() throws Exception {
        mvc.perform(post("/api/venues/not-a-uuid/operational-issues").with(flow.as("vs1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(issue("Air conditioning failure", EVENT_START, EVENT_END)))
                .andExpect(status().isBadRequest());
    }
}
