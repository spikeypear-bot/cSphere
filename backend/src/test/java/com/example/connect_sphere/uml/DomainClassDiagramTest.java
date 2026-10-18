package com.example.connect_sphere.uml;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embedded;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.metamodel.EntityType;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * DEV15: the domain class diagram (see {@link ClassDiagrams}). Most entities
 * hold a link to another entity as a plain UUID column, which a code scan
 * shows as an attribute rather than a relationship. This test reads the
 * foreign keys from the Flyway-migrated database and draws each one whose
 * column maps to such a UUID field, so the diagram shows the relationships of
 * the real schema. The same foreign keys give the multiplicities of the
 * relationships the generator draws for @ManyToOne fields.
 */
@Tag("integration")
@SpringBootTest
class DomainClassDiagramTest {

    // One row per foreign key (a composite key is one row with several
    // columns), with what the multiplicities need: whether every column is
    // NOT NULL (target end: 1 vs 0..1) and whether the columns are unique
    // together (source end: 0..1 vs 0..*).
    private static final String FOREIGN_KEYS = """
            SELECT src.relname AS table_name, dst.relname AS ref_table,
                   array_agg(a.attname::text ORDER BY k.ord) AS columns,
                   bool_and(a.attnotnull) AS not_null,
                   EXISTS (SELECT 1 FROM pg_constraint u
                           WHERE u.conrelid = c.conrelid AND u.contype IN ('p', 'u')
                             AND u.conkey @> c.conkey AND u.conkey <@ c.conkey) AS is_unique
            FROM pg_constraint c
            JOIN pg_class src ON src.oid = c.conrelid
            JOIN pg_class dst ON dst.oid = c.confrelid
            JOIN pg_namespace n ON n.oid = src.relnamespace
            CROSS JOIN LATERAL unnest(c.conkey) WITH ORDINALITY AS k(attnum, ord)
            JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.attnum
            WHERE c.contype = 'f' AND n.nspname = current_schema()
            GROUP BY c.oid, c.conrelid, c.conkey, src.relname, dst.relname
            ORDER BY 1, 3
            """;

    // Whether a status column may be empty: the enum end's 1 vs 0..1.
    private static final String NOT_NULL_COLUMNS = """
            SELECT table_name || '.' || column_name FROM information_schema.columns
            WHERE table_schema = current_schema() AND is_nullable = 'NO'
            """;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private JdbcTemplate jdbc;

    // Classes on the domain diagram that are neither entities nor enums. A
    // new one fails the test: add it here if it is a real domain value type,
    // or leave it out of the diagram with ClassDiagrams.NOT_DOMAIN.
    private static final Set<String> DOMAIN_VALUE_TYPES = Set.of(
            "EffectivePeriod",
            "EquipmentRequestLineId",
            "SerialisedEquipmentId",
            "VenueBecameUnavailable");

    private static final Pattern DECLARATION = Pattern.compile("^(class|enum|interface) (\\S+) (.*)$",
            Pattern.MULTILINE);

    private record ForeignKey(String table, List<String> columns, String refTable, boolean notNull,
            boolean unique) {
    }

    // The field a column maps to; joinedType is set when it is a @ManyToOne or
    // @OneToOne field, whose type is the other end of the relationship.
    private record ColumnField(String path, Class<?> joinedType) {
    }

    @Test
    void generatesDomainDiagramWithForeignKeyRelationships() throws IOException {
        Map<String, List<Class<?>>> entitiesByTable = domainEntitiesByTable();
        List<String> relationships = foreignKeyRelationships(entitiesByTable);
        relationships.addAll(enumRelationships(entitiesByTable));

        List<String> legend = new ArrayList<>(List.of(
                ClassDiagrams.PROPERTY_LEGEND,
                "Multiplicities come from the database foreign keys",
                "Lines labelled with a UUID field: foreign keys the entity stores as an id"));
        entitiesByTable.forEach((table, entities) -> {
            if (entities.size() > 1) {
                legend.add(names(entities) + (entities.size() == 2 ? " both" : " all") + " map table " + table);
            }
        });

        String diagram = ClassDiagrams.generate(ClassDiagrams.domainConfig(legend), "domain", relationships);

        // DEV15 AC: the core domain objects must be on the diagram.
        assertThat(diagram).contains(
                "class Event ",
                "class Venue ",
                "class VenueBooking ",
                "class EquipmentRequest ",
                "class User ",
                "enum UserRole ",
                "class EventSafetyReview ");
        assertThat(diagram).doesNotContain("Controller", "Service ", "Repository", "Dto", "class StatusView ",
                "class OperatingHours ");
        // ...and so must the relationships the code only holds as UUIDs.
        assertThat(diagram)
                .containsPattern("EventSafetyReview \"\\S+\" --> \"\\S+\" Event : eventId")
                .containsPattern("VenueBookingRecord \"\\S+\" --> \"\\S+\" Event : eventId")
                .containsPattern("EventRequest \"\\S+\" --> \"\\S+\" User : coordinatorId");
        // @ManyToOne lines get their multiplicities from the same foreign keys.
        assertThat(diagram).contains(
                "VenueBooking \"0..*\" --> \"1\" VenueEvent : event",
                "VenueBooking \"0..*\" --> \"0..1\" Venue : alternativeVenue",
                "RefreshToken \"0..*\" --> \"1\" User : user");
        assertThat(diagram).doesNotContainPattern("\\w --> \\w+ : (event|venue|user|equipment)\\n");
        assertThat(diagram).contains("Event --> \"1\" EventStatus : status");
        // A list of enum values is an association to the enum, not aggregation.
        assertThat(diagram).contains("EventRequest --> \"0..*\" AccessibilityFeature : accessibilityNeeds",
                "supportedLayouts : List<String>", "eventId : UUID {id}");
        assertThat(diagram).doesNotContain(" o-- ", "@Column", "@ManyToOne", "<<Table>>", "TableName");

        assertThat(DECLARATION.matcher(diagram).results()
                .filter(c -> c.group(1).equals("class") && !c.group(3).startsWith("<<Entity>>"))
                .map(c -> c.group(2)))
                .as("classes on the domain diagram that are neither entities, enums nor known value types")
                .allMatch(DOMAIN_VALUE_TYPES::contains);
    }

    /**
     * The JPA entities that are on the domain diagram, keyed by table. Two
     * entities can map the same table (e.g. a read-only view of it).
     */
    private Map<String, List<Class<?>>> domainEntitiesByTable() {
        return entityManagerFactory.getMetamodel().getEntities().stream()
                .<Class<?>>map(EntityType::getJavaType)
                .filter(type -> !type.getName().matches(ClassDiagrams.NOT_DOMAIN))
                .sorted(Comparator.comparing(Class::getSimpleName))
                .collect(Collectors.groupingBy(DomainClassDiagramTest::tableOf, TreeMap::new, Collectors.toList()));
    }

    private List<String> foreignKeyRelationships(Map<String, List<Class<?>>> entitiesByTable) {
        List<ForeignKey> foreignKeys = jdbc.query(FOREIGN_KEYS, (rs, i) -> new ForeignKey(
                rs.getString("table_name"),
                List.of((String[]) rs.getArray("columns").getArray()),
                rs.getString("ref_table"),
                rs.getBoolean("not_null"),
                rs.getBoolean("is_unique")));

        List<String> relationships = new ArrayList<>();
        for (ForeignKey fk : foreignKeys) {
            List<Class<?>> targets = entitiesByTable.get(fk.refTable());
            if (targets == null) {
                continue;
            }
            // Where several entities map the referenced table, point at the
            // plainest name (Event, not VenueEvent); the legend names the rest.
            Class<?> target = targets.stream()
                    .min(Comparator.comparingInt((Class<?> t) -> t.getSimpleName().length()))
                    .orElseThrow();
            for (Class<?> source : entitiesByTable.getOrDefault(fk.table(), List.of())) {
                Map<String, ColumnField> fields = columnFields(source);
                if (fields.keySet().containsAll(fk.columns())) {
                    List<ColumnField> mapped = fk.columns().stream().map(fields::get).toList();
                    // A @ManyToOne field names its own end (VenueBooking.event is a VenueEvent).
                    Class<?> end = mapped.size() == 1 && mapped.get(0).joinedType() != null
                            ? mapped.get(0).joinedType()
                            : target;
                    String field = mapped.stream().map(ColumnField::path).collect(Collectors.joining(", "));
                    relationships.add("%s \"%s\" --> \"%s\" %s : %s".formatted(
                            source.getSimpleName(), fk.unique() ? "0..1" : "0..*",
                            fk.notNull() ? "1" : "0..1", end.getSimpleName(), field));
                }
            }
        }
        return relationships;
    }

    /**
     * The multiplicity of each single enum field, which the generator draws as
     * a line to the enum without one.
     */
    private List<String> enumRelationships(Map<String, List<Class<?>>> entitiesByTable) {
        Set<String> notNull = Set.copyOf(jdbc.queryForList(NOT_NULL_COLUMNS, String.class));
        List<String> relationships = new ArrayList<>();
        entitiesByTable.forEach((table, entities) -> {
            for (Class<?> entity : entities) {
                for (Field field : entity.getDeclaredFields()) {
                    if (field.getType().isEnum() && !Modifier.isStatic(field.getModifiers())) {
                        relationships.add("%s --> \"%s\" %s : %s".formatted(entity.getSimpleName(),
                                notNull.contains(table + "." + columnOf(field)) ? "1" : "0..1",
                                field.getType().getSimpleName(), field.getName()));
                    }
                }
            }
        });
        return relationships;
    }

    /**
     * Column name to field for the entity's columns, including the columns of
     * an embedded id ("id.requestId"). A column that a @ManyToOne or @OneToOne
     * field joins on maps to that field, even when a plain field or the
     * embedded id maps the same column.
     */
    private static Map<String, ColumnField> columnFields(Class<?> entity) {
        Map<String, ColumnField> columns = new LinkedHashMap<>();
        Map<String, ColumnField> joined = new HashMap<>();
        for (Field field : entity.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            if (field.isAnnotationPresent(ManyToOne.class) || field.isAnnotationPresent(OneToOne.class)) {
                JoinColumn join = field.getAnnotation(JoinColumn.class);
                joined.put(join != null && !join.name().isEmpty() ? join.name() : snakeCase(field.getName()) + "_id",
                        new ColumnField(field.getName(), field.getType()));
            } else if (field.isAnnotationPresent(EmbeddedId.class) || field.isAnnotationPresent(Embedded.class)) {
                for (Field part : field.getType().getDeclaredFields()) {
                    if (!Modifier.isStatic(part.getModifiers())) {
                        columns.put(columnOf(part), new ColumnField(field.getName() + "." + part.getName(), null));
                    }
                }
            } else if (!field.isAnnotationPresent(OneToMany.class) && !field.isAnnotationPresent(ManyToMany.class)
                    && !field.isAnnotationPresent(ElementCollection.class)) {
                columns.put(columnOf(field), new ColumnField(field.getName(), null));
            }
        }
        columns.putAll(joined);
        return columns;
    }

    private static String tableOf(Class<?> entity) {
        Table table = entity.getAnnotation(Table.class);
        return table != null && !table.name().isEmpty() ? table.name() : snakeCase(entity.getSimpleName());
    }

    private static String columnOf(Field field) {
        Column column = field.getAnnotation(Column.class);
        return column != null && !column.name().isEmpty() ? column.name() : snakeCase(field.getName());
    }

    // Spring Boot's default physical naming: expectedAttendance -> expected_attendance.
    private static String snakeCase(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
    }

    private static String names(List<Class<?>> types) {
        return types.stream().map(Class::getSimpleName).collect(Collectors.joining(", "));
    }
}
