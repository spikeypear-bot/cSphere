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
                // VS02 queue only; existing shared booking reads keep their access rules.
                .requestMatchers(HttpMethod.GET, "/api/venue-staff/booking-requests").hasRole("VS")
		    // Reads are open to any authenticated role: Organisers browse
		    // venues while planning, Coordinators search them (EC04). This
		    // must precede the VS rule below, which would otherwise swallow
		    // every method on the same paths.
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
		    .requestMatchers(HttpMethod.POST,
			    "/api/event-requests/*/assign-coordinator",
			    "/api/event-requests/*/approve",
			    "/api/event-requests/*/reject").hasRole("EC")
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
		    .requestMatchers("/api/event-requests/**").hasRole("EO")
		    // Technical Support check availability (TS01); Coordinators need
		    // to see equipment they request (EC07). Reserving and releasing
		    // is TS02, so writes narrow to the technician below.
		    .requestMatchers(HttpMethod.GET, "/api/equipment/**").hasAnyRole("TECHNICIAN", "EC")
		    .requestMatchers("/api/equipment/**").hasRole("TECHNICIAN")
		    // EO09 "Confirmed": only the assigned Coordinator confirms
		    // (enforced in EventService.confirm() itself, same pattern as
		    // approve/reject). Reads are open to both roles that can
		    // legitimately reach one — EventService itself decides whether an
		    // Organiser's own organisation actually owns the event.
		    .requestMatchers(HttpMethod.POST, "/api/events/*/confirm").hasRole("EC")
		    .requestMatchers(HttpMethod.GET, "/api/events/**").hasAnyRole("EO", "EC")
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
