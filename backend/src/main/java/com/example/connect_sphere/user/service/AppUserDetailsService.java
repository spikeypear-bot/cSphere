package com.example.connect_sphere.user.service;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.connect_sphere.user.repository.UserRepository;

/**
 * Loads accounts for Spring Security. Named with an "App" prefix only to avoid
 * reading like the framework interface it implements.
 *
 * This runs at <em>login</em>, not on every request: once the auth slice issues
 * JWTs, an authenticated call is resolved from the token's claims and never
 * touches this class or the database. That is the whole point of stateless auth,
 * and a common source of confusion when people expect a query per request.
 *
 * Declaring this bean also switches off Boot's generated dev password — the
 * "Using generated security password" line disappears from the startup log once
 * a UserDetailsService exists in the context.
 */
@Service
public class AppUserDetailsService implements UserDetailsService {

    private final UserRepository repository;

    public AppUserDetailsService(UserRepository repository) {
        this.repository = repository;
    }

    /**
     * Username is this project's login identifier (not email), so the parameter
     * name is literal here rather than the "whatever you log in with" the
     * interface allows.
     *
     * Throwing {@link UsernameNotFoundException} does not leak which accounts
     * exist: {@code DaoAuthenticationProvider} hides it behind {@code
     * BadCredentialsException} by default, so "no such user" and "wrong password"
     * are indistinguishable to a caller. Keep it that way.
     */
    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        return repository.findByUsername(username)
                .map(UserPrincipal::new)
                .orElseThrow(() -> new UsernameNotFoundException("No user with username: " + username));
    }
}
