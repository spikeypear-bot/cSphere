package com.example.connect_sphere.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig{
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
	    JwtAuthenticationConverter jwtAuthenticationConverter) throws Exception{
	http
	    .csrf(csrf -> csrf.disable())
	    .sessionManagement(session ->
		    session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
	    .httpBasic(Customizer.withDefaults())
	    .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt ->
		    jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)))
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
