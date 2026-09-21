package com.example.connect_sphere.user.service;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import com.example.connect_sphere.user.entity.User;

/**
 * Adapts a {@link User} to Spring Security's {@link UserDetails} contract.
 *
 * Written as a wrapper rather than having {@code User} implement {@code
 * UserDetails} directly: the entity is a domain object, and implementing a
 * framework interface on it would couple the domain model to Spring Security and
 * pull account-status vocabulary into a table that has no such columns.
 *
 * {@code UserDetails} declares only three abstract methods — the four
 * {@code isAccountNon*}/{@code isEnabled} flags are defaults returning true, which
 * is the honest mapping here since `users` stores no enabled/locked/expiry state.
 * If the team later adds those columns, override the flags here.
 */
public class UserPrincipal implements UserDetails {

    private final User user;

    public UserPrincipal(User user) {
        this.user = user;
    }

    /**
     * One authority per user, derived from the role column.
     *
     * The {@code ROLE_} prefix is not decoration: {@code hasRole("EC")} is defined
     * as {@code hasAuthority("ROLE_EC")}, so omitting it here makes every
     * {@code hasRole(...)} check silently fail with a 403.
     */
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        String authority = "ROLE_" + user.getRole().name().toUpperCase(Locale.ROOT);
        return List.of(new SimpleGrantedAuthority(authority));
    }

    @Override
    public String getPassword() {
        return user.getHashedPassword();
    }

    @Override
    public String getUsername() {
        return user.getUsername();
    }

    /** The stable identifier to put in a token's `sub` claim — not the username. */
    public UUID getUserId() {
        return user.getUserId();
    }

    /** Needed by the AU04 data-scoping rules; destined for a token claim. */
    public String getOrganisation() {
        return user.getOrganisation();
    }

    /** The underlying account, for callers that need more than the contract above. */
    public User getUser() {
        return user;
    }
}
