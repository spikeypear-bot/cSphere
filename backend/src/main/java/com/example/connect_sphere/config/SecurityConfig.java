package com.example.connect_sphere.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
	    // Setting them once above is not enough. BasicAuthenticationFilter
	    // and BearerTokenAuthenticationFilter each catch their own
	    // AuthenticationException and call their OWN entry point, never
	    // reaching ExceptionTranslationFilter — so an expired or forged token
	    // would still come back in Spring's default shape unless the same
	    // handler is handed to each of them explicitly.
	    .httpBasic(basic -> basic.authenticationEntryPoint(authenticationEntryPoint))
	    .oauth2ResourceServer(oauth2 -> oauth2
		    .authenticationEntryPoint(authenticationEntryPoint)
		    .accessDeniedHandler(accessDeniedHandler)
		    .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)))
	    .authorizeHttpRequests(authorize ->authorize
		    .requestMatchers("/actuator/**").permitAll()
		    // Login, refresh and logout each carry their own credential in
		    // the body. Requiring a valid access token to renew an expired
		    // one would be a closed loop. Ordered above anyRequest()
		    // because the first matching rule wins.
		    .requestMatchers("/api/auth/**").permitAll()
		    .anyRequest().authenticated());
	return http.build();



    }
    @Bean
    public PasswordEncoder passwordEncoder(){
	return new BCryptPasswordEncoder();

    }


}
