package com.example.connect_sphere.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.example.connect_sphere.user.entity.User;
import com.example.connect_sphere.user.entity.UserRole;
import com.example.connect_sphere.user.repository.UserRepository;

/**
 * ECL-C1 needs someone to sign in as: one Event Coordinator Lead dev account,
 * {@code ecl1}, with the same password as every other seeded account. The
 * repository is mocked and the encoder is the real BCrypt one, so the check is
 * that the stored hash verifies against the shared password, not that some
 * string was passed along.
 */
@Tag("unit")
class DevUserSeederTest {

    private static final String SHARED_DEV_PASSWORD = "123456";

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private UserRepository repository;
    private DevUserSeeder seeder;

    @BeforeEach
    void setUp() {
        repository = mock(UserRepository.class);
        seeder = new DevUserSeeder(repository, passwordEncoder);
    }

    /** Every account the seeder saved during the run. */
    private List<User> seededUsers() {
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(repository, atLeast(0)).save(saved.capture());
        return saved.getAllValues();
    }

    @Test
    void seedsExactlyOneEventCoordinatorLeadAccountNamedEcl1() {
        when(repository.existsByUsername(anyString())).thenReturn(false);

        seeder.run();

        List<User> leads = seededUsers().stream().filter(user -> user.getRole() == UserRole.ecl).toList();
        assertThat(leads).hasSize(1);
        User lead = leads.get(0);
        assertThat(lead.getUsername()).isEqualTo("ecl1");
        assertThat(lead.getOrganisation()).isEqualTo("ConnectSphere");
        assertThat(lead.getUserId()).isNotNull();
    }

    @Test
    void theLeadAccountUsesTheSamePasswordAsTheOtherSeededAccounts() {
        when(repository.existsByUsername(anyString())).thenReturn(false);

        seeder.run();

        List<User> seeded = seededUsers();
        User lead = seeded.stream().filter(user -> "ecl1".equals(user.getUsername())).findFirst().orElseThrow();
        User coordinator = seeded.stream().filter(user -> "ec1".equals(user.getUsername())).findFirst().orElseThrow();
        assertThat(passwordEncoder.matches(SHARED_DEV_PASSWORD, lead.getHashedPassword())).isTrue();
        assertThat(passwordEncoder.matches(SHARED_DEV_PASSWORD, coordinator.getHashedPassword())).isTrue();
        assertThat(passwordEncoder.matches("wrong-password", lead.getHashedPassword())).isFalse();
    }

    @Test
    void doesNotCreateTheLeadAccountAgainWhenItAlreadyExists() {
        when(repository.existsByUsername(anyString())).thenReturn(false);
        when(repository.existsByUsername("ecl1")).thenReturn(true);

        seeder.run();

        // The run still seeds the accounts that are missing; it only skips
        // the one that is already there.
        assertThat(seededUsers()).extracting(User::getUsername).doesNotContain("ecl1").contains("ec1");
    }
}
