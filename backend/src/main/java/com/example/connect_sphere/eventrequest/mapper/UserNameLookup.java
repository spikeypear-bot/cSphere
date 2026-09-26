package com.example.connect_sphere.eventrequest.mapper;

import java.util.UUID;

import org.mapstruct.Named;
import org.springframework.stereotype.Component;

import com.example.connect_sphere.user.entity.User;
import com.example.connect_sphere.user.repository.UserRepository;

/** Used by EventRequestMapper to turn {@code createdBy} into a username. */
@Component
public class UserNameLookup {

    private final UserRepository users;

    public UserNameLookup(UserRepository users) {
        this.users = users;
    }

    @Named("username")
    public String username(UUID userId) {
        return userId == null ? null : users.findById(userId).map(User::getUsername).orElse(null);
    }
}
