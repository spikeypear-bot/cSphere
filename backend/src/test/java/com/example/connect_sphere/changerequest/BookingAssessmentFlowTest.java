package com.example.connect_sphere.changerequest;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.*;
import java.time.LocalTime;
import java.util.stream.IntStream;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.example.connect_sphere.testsupport.FlowSupport;
import com.example.connect_sphere.user.repository.UserRepository;
import com.example.connect_sphere.venue.dto.*;
import com.example.connect_sphere.venue.entity.VenueLayout;
import com.example.connect_sphere.venue.service.VenueService;
import com.example.connect_sphere.venueavailability.AvailabilityService;
import jakarta.persistence.EntityManager;

@Tag("VS17") @Tag("VS07") @Tag("EO04") @Tag("EC08") @Tag("integration")
@SpringBootTest @AutoConfigureMockMvc
class BookingAssessmentFlowTest {
 @Autowired MockMvc mvc; @Autowired UserRepository users; @Autowired JdbcTemplate jdbc;
 @Autowired VenueService venues; @Autowired BookingAssessmentService assessments; @Autowired AvailabilityService availability;
 @Autowired EntityManager em; @Autowired PlatformTransactionManager transactions;
 FlowSupport flow; UUID event,venue,booking,other,request; String name;
 @BeforeEach void setup() { flow=new FlowSupport(mvc,users); }
 void fixture() throws Exception {
  name="VS17 assessment " + UUID.randomUUID();
  event=flow.approvedEvent("eo1","ec1",FlowSupport.requestBody(name,100,"none")); em.flush();
  request=jdbc.queryForObject("SELECT request_id FROM event_requests WHERE event_id=?",UUID.class,event);
  venue=venues.createVenue(new CreateVenueDto("Assessment Hall " + UUID.randomUUID(),200,List.of(VenueLayout.theatre),"Daily",null,List.of(),List.of(),
   IntStream.rangeClosed(1,7).mapToObj(d -> new VenueOperatingHourDto(d,LocalTime.MIN,LocalTime.of(23,59))).toList())).venueId(); em.flush();
  booking=UUID.randomUUID(); other=UUID.randomUUID();
  jdbc.update("INSERT INTO venue_bookings(booking_id,venue_id,event_id,status,start_datetime,end_datetime,setup_duration_minutes,turnaround_duration_minutes) VALUES (?,?,?,'approved','2027-03-10 09:00+08','2027-03-10 12:00+08',30,45)",booking,venue,event);
  UUID second=venues.createVenue(new CreateVenueDto("Unaffected " + UUID.randomUUID(),500,List.of(VenueLayout.theatre),"Daily",null,List.of(),List.of(),
   IntStream.rangeClosed(1,7).mapToObj(d -> new VenueOperatingHourDto(d,LocalTime.MIN,LocalTime.of(23,59))).toList())).venueId(); em.flush();
  jdbc.update("INSERT INTO venue_bookings(booking_id,venue_id,event_id,status,start_datetime,end_datetime) VALUES (?,?,?,'approved','2027-03-10 09:00+08','2027-03-10 12:00+08')",other,second,event);
 }
 String statusOf(UUID id) { return jdbc.queryForObject("SELECT status::text FROM venue_bookings WHERE booking_id=?",String.class,id); }
 void capacity(int value) throws Exception {
  mvc.perform(put("/api/venues/"+venue).with(flow.as("vs1")).contentType(MediaType.APPLICATION_JSON).content("{\"venueCapacity\":"+value+"}"))
   .andExpect(status().isOk()); em.flush(); em.clear();
 }
 int notifications() { return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE venue_booking_id=? AND type::text='venue_suitability_changed'",Integer.class,booking); }
 String restore(String note,UUID revision) { return "{\"assessmentId\":\""+revision+"\",\"note\":\""+note+"\"}"; }
 @Test @Transactional @DisplayName("VS17 (happy): a venue edit invalidates approval across staff, coordinator and organiser while protecting each reservation")
 void invalidate() throws Exception {
  fixture();
  jdbc.update("UPDATE events SET status='confirmed',first_confirmed_at=now(),confirmed_at=now(),safety_approved_at=now() WHERE event_id=?",event); em.clear();
  var before=jdbc.queryForMap("SELECT * FROM venue_bookings WHERE booking_id=?",booking);
  capacity(80);
  assertThat(statusOf(booking)).isEqualTo("attention"); assertThat(statusOf(other)).isEqualTo("approved");
  var after=jdbc.queryForMap("SELECT * FROM venue_bookings WHERE booking_id=?",booking); after.put("status",before.get("status")); assertThat(after).isEqualTo(before);
  assertThat(jdbc.queryForObject("SELECT status::text FROM events WHERE event_id=?",String.class,event)).isEqualTo("pending");
  mvc.perform(get("/api/events/"+event+"/venue-bookings").with(flow.as("ec1"))).andExpect(status().isOk())
   .andExpect(jsonPath("$[?(@.bookingId == '"+booking+"')].status").value(org.hamcrest.Matchers.contains("attention")));
  mvc.perform(get("/api/events/"+event+"/arrangements").with(flow.as("eo1"))).andExpect(status().isOk())
   .andExpect(jsonPath("$.view").value("reconfirming")).andExpect(jsonPath("$.venues[?(@.venueId == '"+venue+"')].requiresAttention").value(org.hamcrest.Matchers.contains(true)));
  mvc.perform(get("/api/events/"+event+"/lifecycle").with(flow.as("ec1"))).andExpect(status().isOk())
   .andExpect(jsonPath("$.items[?(@.key == 'venues')].state").value(org.hamcrest.Matchers.contains("outstanding")));
  assertThat(notifications()).isEqualTo(1);
  assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE venue_booking_id=? AND type::text='venue_arrangements_changed'",Integer.class,booking)).isGreaterThan(0);
  assertThat(availability.conflictsForBooking(venue,UUID.randomUUID(),java.time.OffsetDateTime.parse("2027-03-10T01:00Z"),java.time.OffsetDateTime.parse("2027-03-10T04:00Z"),new com.example.connect_sphere.venueavailability.AvailabilityModels.Settings(0,0))).hasSize(1);
 }
 @Test @Transactional @DisplayName("VS17 (boundary): harmless capacity changes preserve approval and repeated invalid edits produce one alert")
 void deduplicate() throws Exception {
  fixture(); capacity(150); assertThat(statusOf(booking)).isEqualTo("approved"); assertThat(notifications()).isZero();
  capacity(80); capacity(80); assertThat(notifications()).isEqualTo(1);
  assertThat(assessments.view(booking).history()).hasSize(1);
 }
 @Test @Transactional @DisplayName("VS17 (happy): resolved checks require explicit staff reassessment and preserve assessment history")
 void restoreApproval() throws Exception {
  fixture(); capacity(80); UUID revision=assessments.view(booking).history().getFirst().assessmentId();
  mvc.perform(post("/api/venue-bookings/"+booking+"/reassess").with(flow.as("vs1")).contentType(MediaType.APPLICATION_JSON).content(restore("Fixed",revision))).andExpect(status().isConflict());
  capacity(200); assertThat(statusOf(booking)).isEqualTo("attention");
  mvc.perform(post("/api/venue-bookings/"+booking+"/reassess").with(flow.as("vs1")).contentType(MediaType.APPLICATION_JSON).content(restore("Capacity restored and checked",revision)))
   .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("approved")).andExpect(jsonPath("$.history[0].actorName").value("vs1"));
  assertThat(assessments.view(booking).history()).hasSize(2); assertThat(notifications()).isEqualTo(2);
  String publicTimeline=mvc.perform(get("/api/events/"+event+"/timeline").with(flow.as("eo1"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
  assertThat(publicTimeline).doesNotContain("Capacity restored and checked");
  assertThat(assessments.view(booking).history().getFirst().reason()).isEqualTo("Capacity restored and checked");
  mvc.perform(post("/api/venue-bookings/"+booking+"/reassess").with(flow.as("vs1")).contentType(MediaType.APPLICATION_JSON).content(restore("Again",revision))).andExpect(status().isConflict());
 }
 @Test @Transactional @DisplayName("VS17 (negative): role, assignment, stale assessment and comment checks protect reassessment")
 void permissions() throws Exception {
  fixture(); capacity(80); capacity(200); UUID revision=assessments.view(booking).history().getFirst().assessmentId();
  for (String actor:List.of("ec1","eo1")) mvc.perform(post("/api/venue-bookings/"+booking+"/reassess").with(flow.as(actor)).contentType(MediaType.APPLICATION_JSON).content(restore("Checked",revision))).andExpect(status().isForbidden());
  mvc.perform(get("/api/events/"+event+"/venue-assessments").with(flow.as("ec2"))).andExpect(status().isForbidden());
  mvc.perform(post("/api/venue-bookings/"+booking+"/reassess").with(flow.as("vs1")).contentType(MediaType.APPLICATION_JSON).content(restore("Checked",UUID.randomUUID()))).andExpect(status().isConflict());
  mvc.perform(post("/api/venue-bookings/"+booking+"/reassess").with(flow.as("vs1")).contentType(MediaType.APPLICATION_JSON).content(restore(" ",revision))).andExpect(status().isUnprocessableEntity());
  assertThat(statusOf(booking)).isEqualTo("attention"); assertThat(assessments.view(booking).history()).hasSize(1);
 }
 @Test @Transactional @DisplayName("VS17 (happy): pending proposals preserve approval and approved attendance changes invalidate only unsuitable bookings")
 void approvedRequirements() throws Exception {
  fixture(); capacity(150);
  String response=mvc.perform(post("/api/change-requests").with(flow.as("eo1")).contentType(MediaType.APPLICATION_JSON)
   .content("{\"requestId\":\""+request+"\",\"reason\":\"More guests\",\"details\":"+FlowSupport.requestBody(name,200,"none")+"}"))
   .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
  assertThat(statusOf(booking)).isEqualTo("approved");
  String id=flow.read(response).get("changeRequestId").asString();
  String impact=mvc.perform(get("/api/change-requests/"+id).with(flow.as("ec1"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
  var keys=new ArrayList<String>(); flow.read(impact).get("impact").forEach(item -> { if("attention".equals(item.get("status").asString())) keys.add(item.get("key").asString()); });
  String body=new tools.jackson.databind.ObjectMapper().writeValueAsString(Map.of("acknowledged",keys));
  mvc.perform(post("/api/change-requests/"+id+"/approve").with(flow.as("ec1")).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
  assertThat(statusOf(booking)).isEqualTo("attention"); assertThat(statusOf(other)).isEqualTo("approved");
  assertThat(jdbc.queryForObject("SELECT count(*) FROM venue_requirement_reviews WHERE change_request_id=?",Integer.class,UUID.fromString(id))).isEqualTo(2);
 }
 @Test @DisplayName("VS17 (failure): failed assessment notification rolls back venue edit, status and audit")
 void rollback() throws Exception {
  var tx=new TransactionTemplate(transactions); tx.executeWithoutResult(s -> { try { fixture(); } catch(Exception ex) { throw new RuntimeException(ex); } });
  try {
   tx.executeWithoutResult(s -> jdbc.execute("CREATE FUNCTION fail_assessment_notice() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.type::text='venue_suitability_changed' THEN RAISE EXCEPTION 'test failure'; END IF; RETURN NEW; END $$; CREATE TRIGGER test_fail_assessment BEFORE INSERT ON notifications FOR EACH ROW EXECUTE FUNCTION fail_assessment_notice()"));
   assertThatThrownBy(() -> tx.executeWithoutResult(s -> { var input=new UpdateVenueDto(); input.setVenueCapacity(new java.math.BigDecimal(80)); venues.updateVenue(venue,input); })).isInstanceOf(RuntimeException.class);
   assertThat(statusOf(booking)).isEqualTo("approved"); assertThat(assessments.view(booking).history()).isEmpty();
   assertThat(jdbc.queryForObject("SELECT venue_capacity FROM venues WHERE venue_id=?",Integer.class,venue)).isEqualTo(200);
  } finally { tx.executeWithoutResult(s -> jdbc.execute("DROP TRIGGER IF EXISTS test_fail_assessment ON notifications; DROP FUNCTION IF EXISTS fail_assessment_notice()")); }
 }
 @org.junit.jupiter.params.ParameterizedTest
 @org.junit.jupiter.params.provider.ValueSource(strings={"facility","accessibility","hours","layout"})
 @Transactional @DisplayName("VS17 (boundary): facilities, accessibility, hours and written-layout impacts require attention")
 void characteristics(String field) throws Exception {
  fixture(); String body;
  if(field.equals("facility")) {
   jdbc.update("UPDATE events SET required_facilities=ARRAY['projection']::facilities[] WHERE event_id=?",event);
   jdbc.update("UPDATE venues SET venue_facilities=ARRAY['projection']::facilities[] WHERE venue_id=?",venue); body="{\"venueFacilities\":[]}";
  } else if(field.equals("accessibility")) {
   jdbc.update("UPDATE events SET accessibility_needs=ARRAY['step_free_access']::accessibilities[] WHERE event_id=?",event);
   jdbc.update("UPDATE venues SET venue_accessibilities=ARRAY['step_free_access']::accessibilities[] WHERE venue_id=?",venue); body="{\"venueAccessibilities\":[]}";
  } else if(field.equals("hours")) body="{\"operatingHours\":[{\"dayOfWeek\":3,\"openTime\":\"10:00\",\"closeTime\":\"11:00\"}]}";
  else body="{\"supportedLayouts\":[\"classroom\"]}";
  em.clear();
  mvc.perform(put("/api/venues/"+venue).with(flow.as("vs1")).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
  assertThat(statusOf(booking)).isEqualTo("attention"); assertThat(statusOf(other)).isEqualTo("approved");
 }
 @Test @Transactional @DisplayName("VS17 (boundary): new venue defaults do not rewrite existing booking durations or invalidate a suitable booking")
 void defaultDurations() throws Exception {
  fixture(); var before=jdbc.queryForMap("SELECT * FROM venue_bookings WHERE booking_id=?",booking);
  mvc.perform(put("/api/venues/"+venue).with(flow.as("vs1")).contentType(MediaType.APPLICATION_JSON).content("{\"setupMinutes\":90,\"turnaroundMinutes\":90}")).andExpect(status().isOk());
  assertThat(jdbc.queryForMap("SELECT * FROM venue_bookings WHERE booking_id=?",booking)).isEqualTo(before);
 }
 @Test @Transactional @DisplayName("VS17 (happy): cancellation releases an affected booking without altering the other venue")
 void cancelAttention() throws Exception {
  fixture(); capacity(80);
  mvc.perform(post("/api/events/"+event+"/venue-bookings/"+booking+"/cancel").with(flow.as("ec1")).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isOk());
  em.flush();
  assertThat(statusOf(booking)).isEqualTo("cancelled"); assertThat(statusOf(other)).isEqualTo("approved");
  assertThat(availability.conflictsForBooking(venue,UUID.randomUUID(),java.time.OffsetDateTime.parse("2027-03-10T01:00Z"),java.time.OffsetDateTime.parse("2027-03-10T04:00Z"),new com.example.connect_sphere.venueavailability.AvailabilityModels.Settings(0,0))).isEmpty();
 }
 @Test @Transactional @DisplayName("VS17 (cross-cutting): approval snapshots and staff decisions survive a venue invalidation and assessment snapshots capture the edit")
 void preservedHistory() throws Exception {
  fixture();
  jdbc.update("UPDATE events SET expected_attendance=200 WHERE event_id=?",event); em.clear();
  // A suitable initial review is created through the existing approved-change service.
  String asked=mvc.perform(post("/api/change-requests").with(flow.as("eo1")).contentType(MediaType.APPLICATION_JSON)
   .content("{\"requestId\":\""+request+"\",\"reason\":\"More guests\",\"details\":"+FlowSupport.requestBody(name,150,"none")+"}"))
   .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
  UUID change=UUID.fromString(flow.read(asked).get("changeRequestId").asString());
  mvc.perform(post("/api/change-requests/"+change+"/approve").with(flow.as("ec1")).contentType(MediaType.APPLICATION_JSON).content("{\"acknowledged\":[]}")).andExpect(status().isOk());
  UUID review=jdbc.queryForObject("SELECT review_id FROM venue_requirement_reviews WHERE booking_id=?",UUID.class,booking);
  mvc.perform(post("/api/venue-bookings/"+booking+"/requirement-reviews/"+review+"/decision").with(flow.as("vs1")).contentType(MediaType.APPLICATION_JSON).content("{\"outcome\":\"suitable\",\"note\":\"Checked seating\"}")).andExpect(status().isOk());
  var before=jdbc.queryForMap("SELECT * FROM venue_requirement_reviews WHERE review_id=?",review);
  capacity(100);
  assertThat(jdbc.queryForMap("SELECT * FROM venue_requirement_reviews WHERE review_id=?",review)).isEqualTo(before);
  mvc.perform(get("/api/venue-bookings/"+booking+"/requirement-reviews").with(flow.as("vs1"))).andExpect(status().isOk()).andExpect(jsonPath("$[0].outcomeCurrent").value(false));
  assertThat(jdbc.queryForObject("SELECT (previous_venue_snapshot->>'venue_capacity')::int FROM venue_booking_assessments WHERE booking_id=?",Integer.class,booking)).isEqualTo(200);
  assertThat(jdbc.queryForObject("SELECT (venue_snapshot->>'venue_capacity')::int FROM venue_booking_assessments WHERE booking_id=?",Integer.class,booking)).isEqualTo(100);
 }

 @Test @Transactional @DisplayName("VS17 (boundary): descriptive text edits do not invalidate bookings or trigger assessment notifications")
 void descriptiveText() throws Exception {
  fixture();
  jdbc.update("UPDATE venues SET venue_capacity=80 WHERE venue_id=?",venue); em.clear();
  mvc.perform(put("/api/venues/"+venue).with(flow.as("vs1")).contentType(MediaType.APPLICATION_JSON)
   .content("{\"additionalInformation\":\"New entrance directions\",\"operatingInformation\":\"Contact the reception\"}")).andExpect(status().isOk());
  assertThat(statusOf(booking)).isEqualTo("approved"); assertThat(notifications()).isZero();
 }

 private tools.jackson.databind.JsonNode jsonGet(String path,String actor) throws Exception {
  return flow.read(mvc.perform(get(path).with(flow.as(actor))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
 }
 private void assertHoursAcrossRoles(String expected,int days) throws Exception {
  var catalogue=jsonGet("/api/venues/"+venue,"vs1");
  assertThat(catalogue.get("operatingInformation").asString()).isEqualTo(expected);
  assertThat(catalogue.get("operatingHours").size()).isEqualTo(days);
  var staff=jsonGet("/api/venue-bookings/"+booking,"vs1").get("venue");
  assertThat(staff.get("operatingInformation").asString()).isEqualTo(expected);
  assertThat(staff.get("operatingHours").size()).isEqualTo(days);
  boolean existingFound=false;
  for(var b:jsonGet("/api/events/"+event+"/venue-bookings","ec1")) {
   if(booking.toString().equals(b.get("bookingId").asString())) {
    existingFound=true; assertThat(b.get("operatingInformation").asString()).isEqualTo(expected);
    assertThat(b.get("operatingHours").size()).isEqualTo(days);
   }
  }
  assertThat(existingFound).isTrue();
  boolean found=false;
  for(var option:jsonGet("/api/events/"+event+"/venue-options","ec1")) {
   var v=option.get("venue");
   if(venue.toString().equals(v.get("venueId").asString())) {
    found=true; assertThat(v.get("operatingInformation").asString()).isEqualTo(expected);
    assertThat(v.get("operatingHours").size()).isEqualTo(days);
    assertThat(v.get("operatingHours").size()).isEqualTo(days);
   }
  }
  assertThat(found).isTrue(); found=false;
  for(var v:jsonGet("/api/events/"+event+"/arrangements","eo1").get("venues")) {
   if(venue.toString().equals(v.get("venueId").asString())) {
    found=true; assertThat(v.get("operatingInformation").asString()).isEqualTo(expected);
    assertThat(v.get("operatingHours").size()).isEqualTo(days);
   }
  }
  assertThat(found).isTrue();
 }
 @Test @Transactional @DisplayName("VS17 (cross-cutting): saved hours are identical in VS catalogue and booking, EC comparison and EO arrangements")
 void sharedOperatingHours() throws Exception {
  fixture();
  jdbc.update("UPDATE events SET status='confirmed',first_confirmed_at=now(),confirmed_at=now() WHERE event_id=?",event);
  jdbc.update("UPDATE venues SET operating_information='Wrong legacy free text' WHERE venue_id=?",venue); em.clear();
  var expected=com.example.connect_sphere.venue.service.VenueHoursService.summary(IntStream.rangeClosed(1,7).mapToObj(d -> new VenueOperatingHourDto(d,LocalTime.MIN,LocalTime.of(23,59))).toList());
  assertHoursAcrossRoles(expected,7);
  var before=jdbc.queryForMap("SELECT * FROM venue_bookings WHERE booking_id=?",booking);
  mvc.perform(put("/api/venues/"+venue).with(flow.as("vs1")).contentType(MediaType.APPLICATION_JSON)
   .content("{\"operatingHours\":[{\"dayOfWeek\":3,\"openTime\":\"08:00\",\"closeTime\":\"18:00\"}]}")).andExpect(status().isOk());
  em.flush(); em.clear(); assertHoursAcrossRoles("Wednesday 08:00–18:00",1);
  assertThat(jdbc.queryForMap("SELECT * FROM venue_bookings WHERE booking_id=?",booking)).isEqualTo(before);
 }
 @Test @Transactional @DisplayName("VS17 (negative): legacy venue text without a schedule never advertises hours across any role")
 void missingOperatingHours() throws Exception {
  fixture();
  jdbc.update("UPDATE events SET status='confirmed',first_confirmed_at=now(),confirmed_at=now() WHERE event_id=?",event);
  jdbc.update("DELETE FROM venue_operating_hours WHERE venue_id=?",venue);
  jdbc.update("UPDATE venues SET operating_information='Daily 09:00-18:00' WHERE venue_id=?",venue); em.clear();
  assertHoursAcrossRoles("Operating hours not configured",0);
 }

}
