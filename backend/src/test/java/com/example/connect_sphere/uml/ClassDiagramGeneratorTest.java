package com.example.connect_sphere.uml;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * DEV15: the full and auth class diagrams (see {@link ClassDiagrams}). They
 * need only the compiled classes; the domain diagram also reads the database
 * and lives in {@link DomainClassDiagramTest}.
 */
@Tag("unit")
class ClassDiagramGeneratorTest {

    @Test
    void generatesFullDiagram() throws IOException {
        String diagram = ClassDiagrams.generate(ClassDiagrams.fullConfig(), "full", List.of());

        assertThat(diagram).contains(
                "class Event ",
                "class VenueBookingController ",
                "class EventLifecycleService ",
                "interface VenueBookingRepository ",
                "class SecurityConfig ");
        assertThat(diagram).doesNotContain("class Mock ", "MapperImpl", "FlowSupport");
        // Composition only for an entity's embedded id; injected beans are associations.
        assertThat(diagram.lines().filter(line -> line.contains(" *-- ")))
                .containsExactlyInAnyOrder(
                        "EquipmentRequestLine *-- EquipmentRequestLineId : id",
                        "SerialisedEquipment *-- SerialisedEquipmentId : id");
        // A nested record keeps its own box instead of merging with the entity it is named after.
        assertThat(diagram).contains("class OrganiserArrangementsDto.Venue ", "class Venue <<Entity>> ",
                "OrganiserArrangementsDto --> \"0..*\" OrganiserArrangementsDto.Venue : venues");
    }

    @Test
    void generatesAuthDiagram() throws IOException {
        String diagram = ClassDiagrams.generate(ClassDiagrams.authConfig(), "auth", List.of());

        assertThat(diagram).contains(
                "class SecurityConfig ",
                "class JwtConfig ",
                "class AuthController ",
                "class TokenService ",
                "class RefreshTokenService ",
                "class UserPrincipal ",
                "class User ",
                "class RestAuthenticationEntryPoint ",
                "AuthController --> TokenService : tokenService");
        assertThat(diagram).doesNotContain("class Venue ", "DevUserSeeder", "ApiExceptionHandler", " *-- ");
    }
}
