package com.example.connect_sphere.config;

import java.util.Base64;
import java.util.List;
import java.util.Locale;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

@Configuration
public class JwtConfig {

    private final SecretKey key;

    public JwtConfig(@Value("${security.jwt.secret}") String secret) {
        this.key = new SecretKeySpec(Base64.getDecoder().decode(secret), "HmacSHA256");
    }

    @Bean
    public JwtEncoder jwtEncoder() {
        return NimbusJwtEncoder.withSecretKey(key).build();
    }

    /** Same key, same default algorithm — both sides must agree on both. */
    @Bean
    public JwtDecoder jwtDecoder() {
        return NimbusJwtDecoder.withSecretKey(key).build();
    }

    /**
     * Turns the token's `role` claim into the same authority
     * UserPrincipal.getAuthorities() produces, so a caller ends up with identical
     * rights whether they authenticated by Basic or by Bearer.
     *
     * Spring's default converter reads a `scope`/`scp` claim and prefixes SCOPE_.
     * Our tokens carry neither, so without this a fully authenticated user would
     * hold zero authorities — harmless until the first hasRole(...) rule exists,
     * then a blanket 403.
     *
     * Keep this expression in step with UserPrincipal: hasRole("VS") compiles to
     * an exact String.equals against "ROLE_VS", so ROLE_vs is an unrelated string
     * and the mismatch shows up only as a 403 that looks like a bad rule. The
     * claim itself stays lowercase — that is the domain value the frontend reads;
     * the ROLE_ prefix and upper case are Spring Security's convention, and
     * translating into it belongs here rather than in the token.
     */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            String role = jwt.getClaimAsString("role");
            if (role == null) {
                return List.of();
            }
            GrantedAuthority authority =
                    new SimpleGrantedAuthority("ROLE_" + role.toUpperCase(Locale.ROOT));
            return List.of(authority);
        });
        return converter;
    }
}
