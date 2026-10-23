package com.example.connect_sphere.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import com.example.connect_sphere.common.web.RestAccessDeniedHandler;
import com.example.connect_sphere.common.web.RestAuthenticationEntryPoint;

/**
 * {@code @EnableMethodSecurity} switches on the AOP infrastructure behind
 * {@code @PreAuthorize}/{@code @PostAuthorize}. Without it those annotations
 * still compile and still run — they simply do nothing, with no warning, so
 * every guarded method answers 200. It fails open, which is why it is turned on
 * here before the first rule is written rather than alongside it.
 * {@code prePostEnabled} defaults to true; the older
 * {@code @EnableGlobalMethodSecurity} is the deprecated Spring Security 5 form.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig{
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
	    JwtAuthenticationConverter jwtAuthenticationConverter,
	    RestAuthenticationEntryPoint authenticationEntryPoint,
	    RestAccessDeniedHandler accessDeniedHandler) throws Exception{
	http
	    .csrf(csrf -> csrf.disable())
	    .sessionManagement(session ->
		    session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
	    // ExceptionTranslationFilter's handlers: they cover a request that
	    // carried no credentials (401) and one whose principal is real but
	    // not permitted (403).
	    .exceptionHandling(exceptions -> exceptions
		    .authenticationEntryPoint(authenticationEntryPoint)
		    .accessDeniedHandler(accessDeniedHandler))
	    // Repeated here because setting them above is not enough:
	    // BearerTokenAuthenticationFilter catches its own
	    // AuthenticationException and calls its OWN entry point, never reaching
	    // ExceptionTranslationFilter — so an expired or forged token would still
	    // come back in Spring's default shape unless the same handler is handed
	    // to the filter explicitly.
	    //
	    // No httpBasic(): it existed only so authenticated requests were
	    // testable with `curl -u` before tokens worked (D17 stage 3). Keeping it
	    // would send the password on every request with no expiry or revocation,
	    // re-run BCrypt per request under STATELESS, and leave two principal
	    // shapes — UserPrincipal on Basic, Jwt on Bearer — for expressions to
	    // disagree over. Log in and send the Bearer token instead.
	    .oauth2ResourceServer(oauth2 -> oauth2
		    .authenticationEntryPoint(authenticationEntryPoint)
		    .accessDeniedHandler(accessDeniedHandler)
		    .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)))
	    // D20's matrix. First match wins and evaluation stops there, so these
	    // run specific-to-general: a method-scoped rule must precede the
	    // path-wide rule it carves an exception out of. anyRequest() must be
	    // last — Spring rejects a rule declared after it at startup.
	    .authorizeHttpRequests(authorize ->authorize
		    .requestMatchers("/actuator/**").permitAll()
		    // Login, refresh and logout each carry their own credential in
		    // the body. Requiring a valid access token to renew an expired
		    // one would be a closed loop. Ordered above anyRequest()
		    // because the first matching rule wins.
		    .requestMatchers("/api/auth/**").permitAll()
		    // Demo vertical slice illustrating the MVC convention, not a
		    // product endpoint (D20 open question 4). denyAll rather than
		    // deleting the package, which is a separate call.
		    .requestMatchers("/api/mock", "/api/mock/**").denyAll()
                .requestMatchers("/api/venue-bookings/*/holds", "/api/venue-holds/**").hasRole("VS")
                .requestMatchers(HttpMethod.PATCH, "/api/venue-bookings/*/approve", "/api/venue-bookings/*/reject").hasRole("VS")
                .requestMatchers(HttpMethod.PUT, "/api/venue-bookings/*/timing").hasRole("VS")
                .requestMatchers("/api/venues/*/timing-conflicts", "/api/venues/*/timing-conflicts/scan").hasRole("VS")
                // VS02 queue only; existing shared booking reads keep their access rules.
                .requestMatchers(HttpMethod.GET, "/api/venue-staff/booking-requests").hasRole("VS")
		    // Reads are open to any authenticated role: Organisers browse
		    // venues while planning, Coordinators search them (EC04). This
		    // must precede the VS rule below, which would otherwise swallow
		    // every method on the same paths.
		    .requestMatchers("/api/venues/*/unavailability", "/api/venues/*/unavailability/**",
                        "/api/venues/*/occupancy-settings", "/api/venues/*/schedule").hasRole("VS")
		    .requestMatchers("/api/venues/*/operational-issues/**").hasRole("VS")
		    .requestMatchers("/api/coordinator/operational-issues").hasRole("EC")
		    .requestMatchers(HttpMethod.GET, "/api/venues/search").hasRole("EC")
		    .requestMatchers(HttpMethod.GET, "/api/venues", "/api/venues/**").authenticated()
		    // Catalogue authoring: VS07/VS18, widened to Coordinators by
		    // D20 open question 1. The only place this rule lives — the
		    // duplicate @PreAuthorize on VenueService.createVenue was dropped,
		    // since this layer rejects first and a drifting pair would leave
		    // the annotation looking load-bearing when it is not.
		    .requestMatchers("/api/venues/**").hasAnyRole("EC", "VS")
		    // EO09/EO19's minimal Event Coordinator side: the review queue and
		    // the three decision actions. Deliberately narrow HttpMethod+path
		    // matchers so they carve out only themselves from the blanket
		    // hasRole("EO") rule immediately below, which otherwise still
		    // governs every other /api/event-requests/** path exactly as
		    // before — an Organiser's token must not satisfy these, and a
		    // Coordinator's must not satisfy that.
		    .requestMatchers(HttpMethod.GET, "/api/event-requests/queue").hasRole("EC")
		    // EC02 review screen and EC01 clarification. Which requests a
		    // coordinator may open is the assignment check in
		    // EventRequestService; this only decides the role.
		    .requestMatchers(HttpMethod.GET, "/api/event-requests/*/review").hasRole("EC")
		    .requestMatchers(HttpMethod.POST,
			    "/api/event-requests/*/approve",
			    "/api/event-requests/*/reject",
			    "/api/event-requests/*/clarifications").hasRole("EC")
		    // EC-C1: only the assigned Event Coordinator may request or inspect
		    // reassignment status for an event.
		    .requestMatchers(HttpMethod.GET, "/api/events/*/reassignment-requests").hasRole("EC")
		    .requestMatchers(HttpMethod.POST, "/api/events/*/reassignment-requests").hasRole("EC")
		    // EC-C1: event reassignment decisions and queue are Lead-only.
		    .requestMatchers("/api/event-reassignment-requests", "/api/event-reassignment-requests/**").hasRole("ECL")
		    // ELC-C6's assign action and coordinator list share the rule below.
		    // ECL-C1 queue and ECL-C3 review, reject and clarification: Lead
		    // only. Must sit above the blanket EO rule below.
		    .requestMatchers("/api/event-requests/unassigned/**").hasRole("ECL")
		    // ECL-C2 assignments list and ELC-C5 reassign action: Lead only,
		    // above the same blanket rule.
		    .requestMatchers("/api/event-requests/assigned", "/api/event-requests/assigned/**").hasRole("ECL")
		    // Organisers only, deliberately narrower than D20's first draft.
		    // Coordinators are internal (organisation "ConnectSphere") and no
		    // Organiser belongs to it, so scoping them by their own claim would
		    // return an empty list on every call — access that silently shows
		    // nothing reads as a broken feature. They get in when EC01/EC02
		    // exist and requests can be scoped by the Coordinator assigned to
		    // them, which is an assignment rule, not an organisation one.
		    //
		    // This rule decides *whether* a caller reaches the endpoint. *Which
		    // rows* they see is the token's organisation claim, read in
		    // EventRequestController — the filter chain sees the URL, not the row.
		    // EO07: POST /api/event-requests/*/cancel and /*/cancellation-request
		    // are the organiser's and fall under this rule too; which request is
		    // the organisation check in EventCancellationService.
		    .requestMatchers("/api/event-requests/**").hasRole("EO")
		    // Organisers need the catalogue to choose equipment for a request;
		    // Coordinators and technicians use it for availability and requests.
		    .requestMatchers(HttpMethod.GET, "/api/equipment/catalogue").hasAnyRole("EO", "EC", "TECHNICIAN")
		    // Technical Support check availability (TS01); Coordinators need
		    // to see equipment they request (EC07). Reserving and releasing
		    // is TS02, so other equipment reads remain limited below.
		    // ECL-C7: an event's reservations and an equipment request's
		    // availability are about one event and are not scoped to an
		    // assignment, so coordinators (who never use them) no longer reach
		    // them; the Safety Officer reads reservations for the safety check.
		    // Inventory reads (units and their status, TS03) stay open to
		    // coordinators: they describe equipment, not anyone's event.
		    .requestMatchers(HttpMethod.GET, "/api/equipment/events/*/reservations")
			    .hasAnyRole("TECHNICIAN", "SAFETY_OFFICER")
		    .requestMatchers(HttpMethod.GET, "/api/equipment/requests/*/availability").hasRole("TECHNICIAN")
		    .requestMatchers(HttpMethod.GET, "/api/equipment/**").hasAnyRole("TECHNICIAN", "EC")
		    .requestMatchers("/api/equipment/**").hasRole("TECHNICIAN")
		    .requestMatchers(HttpMethod.GET, "/api/equipment-requests/processing",
			    "/api/equipment-requests/*/lines").hasRole("TECHNICIAN")
		    .requestMatchers(HttpMethod.PATCH, "/api/equipment-requests/*/status").hasRole("TECHNICIAN")
		    .requestMatchers(HttpMethod.GET, "/api/events/*/equipment-requests").hasAnyRole("EC", "SAFETY_OFFICER", "ECL")
		    .requestMatchers(HttpMethod.POST, "/api/events/*/equipment-request").hasRole("EC")
		    // SO-C3/SO-C4: the Safety Officer's checklist and decision.
		    .requestMatchers(HttpMethod.POST, "/api/safety/events/*/approval").hasRole("SAFETY_OFFICER")
		    .requestMatchers(HttpMethod.PATCH, "/api/safety/events/*/readiness/items/*").hasRole("SAFETY_OFFICER")
		    .requestMatchers("/api/safety/events/**").hasRole("SAFETY_OFFICER")
		    .requestMatchers(HttpMethod.GET, "/api/events/*/readiness").hasAnyRole("EC", "SAFETY_OFFICER")
		    // EC08: the assigned Coordinator confirms, only after the Safety
		    // Officer approves (enforced in EventLifecycleService.confirm).
		    .requestMatchers(HttpMethod.POST, "/api/events/*/confirm").hasRole("EC")
		    // EC08: the coordinator's lifecycle checklist, send for / withdraw
		    // from safety review. Which event is the assignment check in
		    // EventLifecycleService.
		    .requestMatchers(HttpMethod.GET, "/api/events/*/lifecycle").hasRole("EC")
		    .requestMatchers(HttpMethod.POST, "/api/events/*/safety-review",
			    "/api/events/*/safety-review/withdraw").hasRole("EC")
		    // EO04: organiser's arrangements view. Organiser only; which
		    // events is the organisation check in EventArrangementsService.
		    // Must sit above the GET /api/events/** rule below.
		    .requestMatchers(HttpMethod.GET, "/api/events/*/arrangements").hasRole("EO")
		    // EC03: venue shortlist and booking requests. Must precede the
		    // GET /api/events/** rule below, which would otherwise let an
		    // Organiser read the venue shortlist.
		    .requestMatchers(HttpMethod.GET, "/api/events/*/venue-bookings").hasAnyRole("EC", "SAFETY_OFFICER", "ECL")
		    .requestMatchers("/api/events/*/venue-options", "/api/events/*/venue-bookings/**").hasRole("EC")
		    .requestMatchers(HttpMethod.POST, "/api/events/*/venue-bookings").hasRole("EC")
		    // EC09: Coordinator only. Must sit above the GET /api/events/** rule below.
		    .requestMatchers(HttpMethod.GET, "/api/events/assigned").hasRole("EC")
		    // ECL-C7: the Lead's overview of every coordinator's active events.
		    .requestMatchers(HttpMethod.GET, "/api/events/by-coordinator").hasRole("ECL")
		    // EO07: an event's cancellation requests. The owning organisation's
		    // organisers, the assigned coordinator and the Lead (read-only);
		    // which event is the organisation/assignment check in
		    // EventCancellationService.
		    .requestMatchers(HttpMethod.GET, "/api/events/*/cancellation-requests").hasAnyRole("EO", "EC", "ECL")
		    // ECL-C7: the Lead may read any event (details, timeline) for
		    // oversight; every coordinator action above stays EC-only.
		    .requestMatchers(HttpMethod.GET, "/api/events/**").hasAnyRole("EO", "EC", "ECL")
		    // EO09/EO19: any signed-in role may read/mark-read their own
		    // notifications — scoping is always by the caller's own `sub`
		    // claim (NotificationController), never anything role-specific.
		    .requestMatchers("/api/notifications/**").authenticated()
		    .anyRequest().authenticated());
	return http.build();



    }
    @Bean
    public PasswordEncoder passwordEncoder(){
	return new BCryptPasswordEncoder();

    }


}
