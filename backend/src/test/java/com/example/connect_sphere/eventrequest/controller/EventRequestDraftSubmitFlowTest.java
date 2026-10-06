package com.example.connect_sphere.eventrequest.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

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

import jakarta.persistence.EntityManager;

@Tag("integration")
@Tag("EO01")
@Tag("EO02")
@Tag("EO15")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class EventRequestDraftSubmitFlowTest {

    private static final String START = "2027-03-10T01:00:00Z";
    private static final String END = "2027-03-10T04:00:00Z";

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;

    private FlowSupport flow;
    private String eventName;

    @BeforeEach
    void setUp() {
        flow = new FlowSupport(mvc, users);
        eventName = "EO01 Launch " + UUID.randomUUID();
    }

    private Map<String, String> complete() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("eventName", "\"" + eventName + "\"");
        fields.put("purpose", "\"Product launch\"");
        fields.put("startDatetime", "\"" + START + "\"");
        fields.put("endDatetime", "\"" + END + "\"");
        fields.put("expectedAttendance", "150");
        fields.put("venueRequirements", "\"Theatre seating\"");
        fields.put("accessibilityNeeds", "[\"step_free_access\"]");
        return fields;
    }

    private static String json(Map<String, String> fields) {
        return fields.entrySet().stream()
                .map(field -> "\"" + field.getKey() + "\":" + field.getValue())
                .collect(Collectors.joining(",", "{", "}"));
    }

    private ResultActions save(String as, String body) throws Exception {
        return mvc.perform(post("/api/event-requests").with(flow.as(as))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private UUID draft(String as, String body) throws Exception {
        String response = save(as, body).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        sync();
        return UUID.fromString(flow.read(response).get("requestId").asString());
    }

    private ResultActions update(UUID requestId, String as, String body) throws Exception {
        return mvc.perform(put("/api/event-requests/" + requestId).with(flow.as(as))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions open(UUID requestId, String as) throws Exception {
        return mvc.perform(get("/api/event-requests/" + requestId).with(flow.as(as)));
    }

    private ResultActions submit(UUID requestId, String as) throws Exception {
        return mvc.perform(post("/api/event-requests/" + requestId + "/submit").with(flow.as(as)));
    }

    private ResultActions list(String as) throws Exception {
        return mvc.perform(get("/api/event-requests").with(flow.as(as)));
    }

    private ResultActions reviewQueue() throws Exception {
        return mvc.perform(get("/api/event-requests/queue").with(flow.as("ec1"))).andExpect(status().isOk());
    }

    private static String entry(UUID requestId) {
        return "$[?(@.requestId == '" + requestId + "')]";
    }

    private static String queued(String list, UUID requestId) {
        return "$." + list + "[?(@.requestId == '" + requestId + "')]";
    }

    private void sync() {
        entityManager.flush();
        entityManager.clear();
    }

    private int savedRequests() {
        sync();
        return jdbc.queryForObject(
                "SELECT count(*) FROM event_requests WHERE event_name = ?", Integer.class, eventName);
    }

    private String savedStatus(UUID requestId) {
        sync();
        return jdbc.queryForObject(
                "SELECT status::text FROM event_requests WHERE request_id = ?", String.class, requestId);
    }

    private String savedPurpose(UUID requestId) {
        sync();
        return jdbc.queryForObject(
                "SELECT purpose FROM event_requests WHERE request_id = ?", String.class, requestId);
    }

    private int submissionsRecorded(UUID requestId) {
        sync();
        return jdbc.queryForObject(
                "SELECT count(*) FROM event_request_activity WHERE request_id = ? AND activity_type = 'submitted'",
                Integer.class, requestId);
    }

    @Test
    void anIncompleteRequestCanBeSavedAsADraftForTheOrganisersOrganisation() throws Exception {
        save("eo1", "{\"eventName\":\"" + eventName + "\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andExpect(jsonPath("$.eventName").value(eventName))
                .andExpect(jsonPath("$.status").value("draft"))
                .andExpect(jsonPath("$.organisation").value("Acme Pte Ltd"))
                .andExpect(jsonPath("$.purpose").isEmpty())
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());

        assertThat(savedRequests()).isEqualTo(1);
    }

    @Test
    void aRequestCanBeStartedWithNoFieldsFilledIn() throws Exception {
        save("eo1", "{}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("draft"))
                .andExpect(jsonPath("$.eventName").isEmpty());
    }

    @Test
    void aSavedDraftCanBeReopenedWithEverythingThatWasSaved() throws Exception {
        Map<String, String> fields = complete();
        fields.put("description", "\"Keynote and demos\"");
        fields.put("equipmentRequirements", "\"Two projectors\"");
        fields.put("registrationNeeds", "true");
        UUID requestId = draft("eo1", json(fields));

        open(requestId, "eo1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(requestId.toString()))
                .andExpect(jsonPath("$.status").value("draft"))
                .andExpect(jsonPath("$.eventName").value(eventName))
                .andExpect(jsonPath("$.purpose").value("Product launch"))
                .andExpect(jsonPath("$.description").value("Keynote and demos"))
                .andExpect(jsonPath("$.startDatetime").value(START))
                .andExpect(jsonPath("$.endDatetime").value(END))
                .andExpect(jsonPath("$.expectedAttendance").value(150))
                .andExpect(jsonPath("$.venueRequirements").value("Theatre seating"))
                .andExpect(jsonPath("$.equipmentRequirements").value("Two projectors"))
                .andExpect(jsonPath("$.accessibilityNeeds", contains("step_free_access")))
                .andExpect(jsonPath("$.registrationNeeds").value(true));
    }

    @Test
    void aDraftCanBeEditedAndSavedAgainWithoutBeingSubmitted() throws Exception {
        UUID requestId = draft("eo1", "{\"eventName\":\"" + eventName + "\"}");
        Map<String, String> changed = complete();
        changed.put("purpose", "\"Annual dinner\"");
        changed.put("expectedAttendance", "80");

        update(requestId, "eo1", json(changed))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(requestId.toString()))
                .andExpect(jsonPath("$.status").value("draft"))
                .andExpect(jsonPath("$.purpose").value("Annual dinner"))
                .andExpect(jsonPath("$.expectedAttendance").value(80));

        assertThat(savedRequests()).isEqualTo(1);
        assertThat(savedStatus(requestId)).isEqualTo("draft");
        open(requestId, "eo1")
                .andExpect(jsonPath("$.purpose").value("Annual dinner"))
                .andExpect(jsonPath("$.expectedAttendance").value(80));
    }

    @Test
    void aSaveThatFailsLeavesTheLastSavedVersionInPlace() throws Exception {
        UUID requestId = draft("eo1", json(complete()));
        Map<String, String> broken = complete();
        broken.put("purpose", "\"Should not be saved\"");
        broken.put("accessibilityNeeds", "[\"not_a_real_feature\"]");

        update(requestId, "eo1", json(broken)).andExpect(status().isBadRequest());

        assertThat(savedPurpose(requestId)).isEqualTo("Product launch");
        open(requestId, "eo1")
                .andExpect(jsonPath("$.purpose").value("Product launch"))
                .andExpect(jsonPath("$.accessibilityNeeds", contains("step_free_access")));
    }

    @Test
    void aDraftIsNotShownToCoordinatorsForReview() throws Exception {
        UUID requestId = draft("eo1", json(complete()));

        reviewQueue()
                .andExpect(jsonPath(queued("unassigned", requestId), empty()))
                .andExpect(jsonPath(queued("needsReview", requestId), empty()))
                .andExpect(jsonPath(queued("awaitingOrganiser", requestId), empty()));
    }

    @Test
    void anOrganiserInTheSameOrganisationCanOpenAndEditTheDraft() throws Exception {
        UUID requestId = draft("eo1", json(complete()));
        Map<String, String> changed = complete();
        changed.put("purpose", "\"Edited by a colleague\"");

        open(requestId, "eo2").andExpect(status().isOk()).andExpect(jsonPath("$.eventName").value(eventName));
        update(requestId, "eo2", json(changed)).andExpect(status().isOk());

        assertThat(savedPurpose(requestId)).isEqualTo("Edited by a colleague");
    }

    @Test
    void anotherOrganisationCannotOpenEditOrSubmitTheDraftAndItIsUnchanged() throws Exception {
        UUID requestId = draft("eo1", json(complete()));
        Map<String, String> changed = complete();
        changed.put("purpose", "\"Changed by an outsider\"");

        open(requestId, "eo3").andExpect(status().isNotFound());
        update(requestId, "eo3", json(changed)).andExpect(status().isNotFound());
        submit(requestId, "eo3").andExpect(status().isNotFound());

        assertThat(savedPurpose(requestId)).isEqualTo("Product launch");
        assertThat(savedStatus(requestId)).isEqualTo("draft");
        assertThat(submissionsRecorded(requestId)).isZero();
    }

    @Test
    void aCompleteDraftCanBeSubmittedAndItsStatusChangesFromDraftToPending() throws Exception {
        UUID requestId = draft("eo1", json(complete()));

        submit(requestId, "eo1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(requestId.toString()))
                .andExpect(jsonPath("$.status").value("pending"));

        assertThat(savedStatus(requestId)).isEqualTo("pending");
        open(requestId, "eo1").andExpect(jsonPath("$.status").value("pending"));
    }

    @Test
    void submittingRecordsWhenTheRequestWasSubmitted() throws Exception {
        UUID requestId = draft("eo1", json(complete()));

        submit(requestId, "eo1").andExpect(status().isOk());

        assertThat(submissionsRecorded(requestId)).isEqualTo(1);
        mvc.perform(get("/api/event-requests/" + requestId + "/timeline").with(flow.as("eo1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.type == 'submitted')].fromStatus", contains("draft")))
                .andExpect(jsonPath("$[?(@.type == 'submitted')].toStatus", contains("pending")))
                .andExpect(jsonPath("$[?(@.type == 'submitted')].actorName", contains("eo1")))
                .andExpect(jsonPath("$[?(@.type == 'submitted')].occurredAt", contains(notNullValue())));
    }

    @Test
    void aSubmittedRequestBecomesAvailableToCoordinatorsForReview() throws Exception {
        UUID requestId = draft("eo1", json(complete()));

        submit(requestId, "eo1").andExpect(status().isOk());
        sync();

        reviewQueue()
                .andExpect(jsonPath(queued("unassigned", requestId) + ".eventName", contains(eventName)))
                .andExpect(jsonPath(queued("unassigned", requestId) + ".status", contains("pending")));
    }

    @Test
    void optionalInformationIsNotNeededToSubmit() throws Exception {
        Map<String, String> fields = complete();
        fields.remove("description");
        fields.remove("equipmentRequirements");
        fields.remove("registrationNeeds");
        UUID requestId = draft("eo1", json(fields));

        submit(requestId, "eo1").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("pending"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"eventName", "purpose", "startDatetime", "endDatetime", "expectedAttendance",
            "venueRequirements", "accessibilityNeeds"})
    void aDraftMissingOneRequiredFieldCannotBeSubmittedAndTheFieldIsNamed(String missing) throws Exception {
        Map<String, String> fields = complete();
        fields.remove(missing);
        UUID requestId = draft("eo1", json(fields));

        submit(requestId, "eo1")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.missingFields", contains(missing)));

        assertThat(savedStatus(requestId)).isEqualTo("draft");
        assertThat(submissionsRecorded(requestId)).isZero();
    }

    @Test
    void submittingAnEmptyDraftNamesEveryRequiredFieldAndLeavesItADraft() throws Exception {
        UUID requestId = draft("eo1", "{}");

        submit(requestId, "eo1")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.missingFields", containsInAnyOrder("eventName", "purpose", "startDatetime",
                        "endDatetime", "expectedAttendance", "venueRequirements", "accessibilityNeeds")));

        assertThat(savedStatus(requestId)).isEqualTo("draft");
        reviewQueue().andExpect(jsonPath(queued("unassigned", requestId), empty()));
    }

    @Test
    void aRequestWhoseEndIsBeforeItsStartCannotBeSubmitted() throws Exception {
        Map<String, String> fields = complete();
        fields.put("startDatetime", "\"" + END + "\"");
        fields.put("endDatetime", "\"" + START + "\"");
        UUID requestId = draft("eo1", json(fields));

        submit(requestId, "eo1")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").isNotEmpty());

        assertThat(savedStatus(requestId)).isEqualTo("draft");
        assertThat(submissionsRecorded(requestId)).isZero();
    }

    @Test
    void aSubmittedRequestCanNoLongerBeEditedOrSubmittedAsADraft() throws Exception {
        UUID requestId = draft("eo1", json(complete()));
        submit(requestId, "eo1").andExpect(status().isOk());
        sync();
        Map<String, String> changed = complete();
        changed.put("purpose", "\"Edited after submitting\"");

        update(requestId, "eo1", json(changed)).andExpect(status().isConflict());
        submit(requestId, "eo1").andExpect(status().isConflict());

        assertThat(savedPurpose(requestId)).isEqualTo("Product launch");
        assertThat(savedStatus(requestId)).isEqualTo("pending");
        assertThat(submissionsRecorded(requestId)).isEqualTo(1);
    }

    @Test
    void theListShowsOnlyTheCallersOrganisationWithEachRequestsStatusAndLastUpdate() throws Exception {
        UUID draftId = draft("eo1", json(complete()));
        UUID submittedId = draft("eo1", json(complete()));
        submit(submittedId, "eo1").andExpect(status().isOk());
        UUID otherOrganisationId = draft("eo3", json(complete()));

        list("eo1")
                .andExpect(status().isOk())
                .andExpect(jsonPath(entry(draftId) + ".status", contains("draft")))
                .andExpect(jsonPath(entry(draftId) + ".updatedAt", contains(notNullValue())))
                .andExpect(jsonPath(entry(submittedId) + ".status", contains("pending")))
                .andExpect(jsonPath(entry(submittedId) + ".updatedAt", contains(notNullValue())))
                .andExpect(jsonPath(entry(otherOrganisationId), empty()));
        list("eo3")
                .andExpect(status().isOk())
                .andExpect(jsonPath(entry(otherOrganisationId) + ".status", contains("draft")))
                .andExpect(jsonPath(entry(draftId), empty()))
                .andExpect(jsonPath(entry(submittedId), empty()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ec1", "vs1", "ts1", "att1"})
    void otherRolesCannotCreateOrListEventRequestsAndNothingIsSaved(String username) throws Exception {
        save(username, json(complete())).andExpect(status().isForbidden());
        list(username).andExpect(status().isForbidden());

        assertThat(savedRequests()).isZero();
    }

    @Test
    void anOrganiserCannotUseCoordinatorVenueStaffOrTechnicalSupportFunctions() throws Exception {
        UUID requestId = draft("eo1", json(complete()));
        submit(requestId, "eo1").andExpect(status().isOk());

        mvc.perform(get("/api/event-requests/queue").with(flow.as("eo1"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/event-requests/" + requestId + "/approve").with(flow.as("eo1")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/venue-staff/booking-requests").with(flow.as("eo1")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/equipment-requests/processing").with(flow.as("eo1")))
                .andExpect(status().isForbidden());

        assertThat(savedStatus(requestId)).isEqualTo("pending");
    }

    @Test
    void anUnauthenticatedCallerCannotCreateOrListEventRequests() throws Exception {
        mvc.perform(post("/api/event-requests").contentType(MediaType.APPLICATION_JSON).content(json(complete())))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/event-requests")).andExpect(status().isUnauthorized());

        assertThat(savedRequests()).isZero();
    }
}
