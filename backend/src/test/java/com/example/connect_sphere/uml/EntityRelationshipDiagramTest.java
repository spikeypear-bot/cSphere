package com.example.connect_sphere.uml;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * DEV15: the ER diagram of the Flyway-migrated schema, written to
 * docs/er-diagrams/schema.generated.puml: every table and column, the primary
 * and foreign keys, the Postgres enum types, and a crow's-foot line per
 * foreign key. Like the class diagrams, every backend test run regenerates
 * it and CI commits the regenerated copy to main after each merge.
 */
@Tag("integration")
@Tag("DEV15")
@SpringBootTest
class EntityRelationshipDiagramTest {

    // Surefire runs from backend/, so this is docs/er-diagrams/ at the repo root.
    private static final Path OUTPUT = Path.of("..", "docs", "er-diagrams", "schema.generated.puml");

    // Flyway's own bookkeeping and the mock demo slice are not the design.
    private static final Set<String> NOT_DESIGN = Set.of("flyway_schema_history", "mock", "mock_references");

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("DEV15 AC2 (happy): the ER diagram shows every table, its keys and its foreign keys from the migrated schema")
    void generatesSchemaDiagram() throws IOException {
        String diagram = generate();

        assertThat(diagram).contains(
                "entity venue_bookings {",
                "entity venue_booking_holds {",
                "\t* event_id : uuid <<PK>> <<FK>>",
                "\t* expires_at : timestamp with time zone",
                "enum user_role {");
        // Crow's feet from the foreign keys: an event has at most one safety
        // review (its key is the event's id), many bookings and many holds.
        assertThat(diagram).contains(
                "event_safety_reviews |o--|| events : event_id",
                "venue_bookings }o--|| events : event_id",
                "venue_bookings }o--o| venue_bookings : replaces_booking_id",
                "venue_booking_holds }o--|| venue_bookings : booking_id");
        assertThat(diagram).doesNotContain("flyway_schema_history", "entity mock");
    }

    private String generate() throws IOException {
        Map<String, List<DatabaseSchema.Column>> tables = new LinkedHashMap<>();
        for (DatabaseSchema.Column column : DatabaseSchema.columns(jdbc)) {
            if (!NOT_DESIGN.contains(column.table())) {
                tables.computeIfAbsent(column.table(), table -> new ArrayList<>()).add(column);
            }
        }

        List<String> lines = new ArrayList<>(List.of(
                "@startuml",
                "",
                "hide circle",
                "legend top left",
                "* column: NOT NULL",
                "<<PK>> primary key, <<FK>> foreign key",
                "Line ends: }o zero or many, |o zero or one, || exactly one",
                "Postgres enum types are listed on their own; columns name them as their type",
                "endlegend",
                ""));
        tables.forEach((table, columns) -> {
            lines.add("entity " + table + " {");
            // Key columns above the line, the rest below, as in an ER diagram.
            columns.stream().filter(DatabaseSchema.Column::primaryKey).map(EntityRelationshipDiagramTest::column)
                    .forEach(lines::add);
            lines.add("\t--");
            columns.stream().filter(column -> !column.primaryKey()).map(EntityRelationshipDiagramTest::column)
                    .forEach(lines::add);
            lines.add("}");
            lines.add("");
        });
        DatabaseSchema.enumTypes(jdbc).forEach((type, values) -> {
            lines.add("enum " + type + " {");
            values.forEach(value -> lines.add("\t" + value));
            lines.add("}");
            lines.add("");
        });
        for (DatabaseSchema.ForeignKey fk : DatabaseSchema.foreignKeys(jdbc)) {
            if (tables.containsKey(fk.table()) && tables.containsKey(fk.refTable())) {
                lines.add("%s %s--%s %s : %s".formatted(fk.table(), fk.unique() ? "|o" : "}o",
                        fk.notNull() ? "||" : "o|", fk.refTable(), String.join(", ", fk.columns())));
            }
        }
        lines.add("");
        lines.add("@enduml");

        // "\n" on every OS, so CI's up-to-date check compares like with like.
        String diagram = String.join("\n", lines);
        Files.createDirectories(OUTPUT.getParent());
        Files.writeString(OUTPUT, diagram, StandardCharsets.UTF_8);
        return diagram;
    }

    private static String column(DatabaseSchema.Column column) {
        return "\t%s%s : %s%s%s".formatted(column.notNull() ? "* " : "", column.name(), column.type(),
                column.primaryKey() ? " <<PK>>" : "", column.foreignKey() ? " <<FK>>" : "");
    }
}
