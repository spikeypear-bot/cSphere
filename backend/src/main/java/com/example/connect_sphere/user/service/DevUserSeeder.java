package com.example.connect_sphere.user.service;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.example.connect_sphere.user.entity.User;
import com.example.connect_sphere.user.entity.UserRole;
import com.example.connect_sphere.user.repository.UserRepository;

/**
 * Creates five accounts per role so every role can be exercised locally.
 *
 * Seed data lives in a runner rather than a Flyway migration on purpose: these
 * are environment-specific dev fixtures, not schema, and migrations are immutable
 * once applied — changing a seeded password would otherwise mean writing a new
 * V{n} file every time. Doing it here also means the passwords are hashed by the
 * very same {@link PasswordEncoder} bean that verifies them at login, so the two
 * sides cannot disagree about the hash format.
 *
 * {@code @Profile("dev")} is the safety gate: this creates accounts with a known
 * password and must never run anywhere real.
 *
 * Note this also runs inside {@code @SpringBootTest}, because the project has no
 * test profile and inherits {@code spring.profiles.active=dev} — harmless, since
 * the existence check makes re-runs no-ops.
 */
@Component
@Profile("dev")
public class DevUserSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DevUserSeeder.class);

    /** Dev-only, and committed to git on purpose — never reuse it anywhere real. */
    private static final String DEV_PASSWORD = "123456";

    /** Internal staff (EC/VS/Technician) all belong to the operating company. */
    private static final String INTERNAL_ORG = "ConnectSphere";

    /**
     * Three client organisations, so AU04 data-scoping has something to scope.
     * Attendees are deliberately given the same organisations as the Event
     * Organisers, which makes eo1/att1 a ready-made "same org" pair and
     * eo1/att3 a "different org" pair for access-control tests.
     */
    private static final String ORG_ACME = "Acme Pte Ltd";
    private static final String ORG_GLOBEX = "Globex Holdings";
    private static final String ORG_INITECH = "Initech Asia";

    private static final List<SeedUser> SEED_USERS = List.of(
            // Event Coordinators — internal
            new SeedUser("ec1", "ec1@connectsphere.test", UserRole.ec, INTERNAL_ORG),
            new SeedUser("ec2", "ec2@connectsphere.test", UserRole.ec, INTERNAL_ORG),
            new SeedUser("ec3", "ec3@connectsphere.test", UserRole.ec, INTERNAL_ORG),
            new SeedUser("ec4", "ec4@connectsphere.test", UserRole.ec, INTERNAL_ORG),
            new SeedUser("ec5", "ec5@connectsphere.test", UserRole.ec, INTERNAL_ORG),

            // Event Organisers — external clients, spread across three organisations
            new SeedUser("eo1", "eo1@acme.test", UserRole.eo, ORG_ACME),
            new SeedUser("eo2", "eo2@acme.test", UserRole.eo, ORG_ACME),
            new SeedUser("eo3", "eo3@globex.test", UserRole.eo, ORG_GLOBEX),
            new SeedUser("eo4", "eo4@globex.test", UserRole.eo, ORG_GLOBEX),
            new SeedUser("eo5", "eo5@initech.test", UserRole.eo, ORG_INITECH),

            // Venue Staff — internal
            new SeedUser("vs1", "vs1@connectsphere.test", UserRole.vs, INTERNAL_ORG),
            new SeedUser("vs2", "vs2@connectsphere.test", UserRole.vs, INTERNAL_ORG),
            new SeedUser("vs3", "vs3@connectsphere.test", UserRole.vs, INTERNAL_ORG),
            new SeedUser("vs4", "vs4@connectsphere.test", UserRole.vs, INTERNAL_ORG),
            new SeedUser("vs5", "vs5@connectsphere.test", UserRole.vs, INTERNAL_ORG),

            // Attendees — matched to the Event Organisers' organisations
            new SeedUser("att1", "att1@acme.test", UserRole.attendee, ORG_ACME),
            new SeedUser("att2", "att2@acme.test", UserRole.attendee, ORG_ACME),
            new SeedUser("att3", "att3@globex.test", UserRole.attendee, ORG_GLOBEX),
            new SeedUser("att4", "att4@globex.test", UserRole.attendee, ORG_GLOBEX),
            new SeedUser("att5", "att5@initech.test", UserRole.attendee, ORG_INITECH),

            // Technical Support Staff — internal
            new SeedUser("tech1", "tech1@connectsphere.test", UserRole.technician, INTERNAL_ORG),
            new SeedUser("tech2", "tech2@connectsphere.test", UserRole.technician, INTERNAL_ORG),
            new SeedUser("tech3", "tech3@connectsphere.test", UserRole.technician, INTERNAL_ORG),
            new SeedUser("tech4", "tech4@connectsphere.test", UserRole.technician, INTERNAL_ORG),
            new SeedUser("tech5", "tech5@connectsphere.test", UserRole.technician, INTERNAL_ORG));

    private final UserRepository repository;
    private final PasswordEncoder passwordEncoder;

    public DevUserSeeder(UserRepository repository, PasswordEncoder passwordEncoder) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * One transaction for the whole batch, and one BCrypt hash reused across every
     * account — hashing is deliberately slow, so encoding once instead of 25 times
     * keeps startup quick. Safe here only because every seeded account shares the
     * same password; never reuse a hash across different passwords.
     */
    @Override
    @Transactional
    public void run(String... args) {
        String hashedPassword = passwordEncoder.encode(DEV_PASSWORD);
        int created = 0;

        for (SeedUser seed : SEED_USERS) {
            if (repository.existsByUsername(seed.username())) {
                continue;
            }
            User user = new User();
            user.setUserId(UUID.randomUUID());
            user.setUsername(seed.username());
            user.setEmail(seed.email());
            user.setHashedPassword(hashedPassword);
            user.setRole(seed.role());
            user.setOrganisation(seed.organisation());
            // createdAt is left alone — the column's DEFAULT CURRENT_TIMESTAMP
            // supplies it, see User.createdAt.
            repository.save(user);
            created++;
        }

        if (created > 0) {
            log.info("Dev seed: created {} of {} accounts (password \"{}\" for all)",
                    created, SEED_USERS.size(), DEV_PASSWORD);
        } else {
            log.info("Dev seed: all {} accounts already present, nothing to do",
                    SEED_USERS.size());
        }
    }

    /** One seeded account. Everything else (id, hash, timestamp) is derived. */
    private record SeedUser(String username, String email, UserRole role, String organisation) {
    }
}
