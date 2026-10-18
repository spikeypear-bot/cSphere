package com.example.connect_sphere.uml;

import de.elnarion.util.plantuml.generator.classdiagram.PlantUMLClassDiagramGenerator;
import de.elnarion.util.plantuml.generator.classdiagram.config.PlantUMLClassDiagramConfig;
import de.elnarion.util.plantuml.generator.classdiagram.config.PlantUMLClassDiagramConfigBuilder;
import de.elnarion.util.plantuml.generator.classdiagram.config.VisibilityType;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * DEV15: shared PlantUML class diagram settings for the three diagram levels,
 * written to docs/class-diagrams/:
 * <ul>
 * <li>domain — entities, enums and value types, plus the foreign keys the
 * entities only hold as UUIDs ({@link DomainClassDiagramTest})</li>
 * <li>full — every layer: controllers, services, repositories, DTOs, config</li>
 * <li>auth — login, JWT and refresh-token classes and the security config</li>
 * </ul>
 * Every backend test run regenerates them, and CI fails a PR whose committed
 * diagrams differ from the code, so commit them whenever a class changes shape.
 */
final class ClassDiagrams {

    static final String BASE_PACKAGE = "com.example.connect_sphere";

    // Surefire runs from backend/, so this is docs/class-diagrams/ at the repo root.
    private static final Path OUTPUT_DIR = Path.of("..", "docs", "class-diagrams");

    // Every blacklist below is matched against the fully qualified class name,
    // and every one starts from this. Anonymous classes ($1, $2, ...) are
    // compiler output, not design. The scan runs on the test classpath, so
    // public test helpers (testsupport.FlowSupport) would otherwise appear;
    // package-private test classes are already skipped by the scanner.
    private static final String NOT_DESIGN = String.join("|",
            ".*\\$\\d+",
            ".*\\.testsupport\\..*",
            ".*Tests?");

    // Domain level: drop web/service/persistence plumbing (including
    // configuration and scheduled jobs kept next to a feature, like
    // HoldClockConfig and VenueHoldExpiryJob), request/response shapes, the
    // mock scaffolding and nested helper types. The last line names
    // the @Components that live outside a service package, AvailabilityModels
    // (it only holds nested records), StatusView (the status shape every API
    // response carries) and OperatingHours (a static helper with no state).
    // DomainClassDiagramTest fails when a class that is neither an entity, an
    // enum nor a known value type reaches the diagram.
    static final String NOT_DOMAIN = String.join("|",
            NOT_DESIGN,
            ".*\\$.*",
            ".*\\.(mock|config|common\\.web|controller|service|repository|dto|mapper)\\..*",
            ".*(Controller|Service|Repository|Dto|Response|Exception|Seeder|Application|MapperImpl|Config|Job)",
            ".*\\.(Add|Create|Reserve|Submit|Update|SafetyApproval)\\w*",
            ".*\\.(JourneyStatusResolver|ExistingTimingConflictScan|AvailabilityModels|StatusView|OperatingHours)");

    // Full level: everything except the mock scaffolding and MapStruct's
    // generated *MapperImpl classes (the hand-written mapper interfaces stay).
    private static final String NOT_HAND_WRITTEN = String.join("|",
            NOT_DESIGN,
            ".*\\.mock\\..*",
            ".*MapperImpl");

    // Auth level: user/, config/ and common/web/ hold the auth classes, minus
    // the dev account seeder and the general (non-auth) exception handler.
    private static final List<String> AUTH_PACKAGES = List.of(
            BASE_PACKAGE + ".user",
            BASE_PACKAGE + ".config",
            BASE_PACKAGE + ".common.web");
    private static final String NOT_AUTH = String.join("|",
            NOT_DESIGN,
            ".*\\.DevUserSeeder",
            ".*\\.ApiExceptionHandler");

    // Lombok/record accessors and Object overrides would bury the real operations.
    private static final String ACCESSORS = "(get|set|is)[A-Z].*|equals|hashCode|toString|canEqual";

    // The generator marks a field "+" when it has a getter and a setter, which
    // reads as "public" to anyone who does not know that.
    static final String PROPERTY_LEGEND = "+ field: has a getter and a setter (every field is private in the code)";

    // What the generator writes, read by tidy(). Classes are written with
    // their fully qualified names (nested ones as Outer$Inner) and shortened
    // there, because the generator's own short names give a nested record and
    // an entity of the same name (OrganiserArrangementsDto.Venue, Venue) one box.
    private static final Pattern DECLARATION = Pattern.compile("^(?:abstract class|class|enum|interface) (\\S+)");
    private static final Pattern QUALIFIED_NAME = Pattern.compile("\\b(?:[a-z_]\\w*\\.)+[A-Z]\\w*(?:\\$\\w+)*");
    private static final Pattern ANNOTATION = Pattern.compile("@\\w+(?:\\(\"[^\"]*\"\\))?");
    private static final Pattern FIELD = Pattern.compile("^(\t\\{field\\}.*?)(\\w+) : .*$");
    private static final Pattern NO_ARG_METHOD = Pattern.compile("^\t\\{method\\} \\+(\\w+) \\(\\) :.*$");
    private static final Pattern RELATIONSHIP = Pattern.compile(
            "^(\\S+)\\s+(?:(\"[^\"]*\")\\s+)?(\\S+)\\s+(?:(\"[^\"]*\")\\s+)?(\\S+)(.*)$");
    private static final Set<String> ARROWS = Set.of("-->", "*--", "o--", "--|>", "..|>", "..>", "--");

    private ClassDiagrams() {
    }

    static PlantUMLClassDiagramConfig domainConfig(List<String> legend) {
        return withSharedSettings(new PlantUMLClassDiagramConfigBuilder(NOT_DOMAIN, List.of(BASE_PACKAGE)), legend);
    }

    static PlantUMLClassDiagramConfig fullConfig() {
        return withSharedSettings(new PlantUMLClassDiagramConfigBuilder(NOT_HAND_WRITTEN, List.of(BASE_PACKAGE)),
                List.of(PROPERTY_LEGEND));
    }

    static PlantUMLClassDiagramConfig authConfig() {
        return withSharedSettings(new PlantUMLClassDiagramConfigBuilder(NOT_AUTH, AUTH_PACKAGES),
                List.of(PROPERTY_LEGEND));
    }

    private static PlantUMLClassDiagramConfig withSharedSettings(PlantUMLClassDiagramConfigBuilder builder,
            List<String> legend) {
        List<String> header = new ArrayList<>(List.of(
                "hide circle",
                "skinparam classAttributeIconSize 0",
                // A dot in a name (OrganiserArrangementsDto.Venue) is part of
                // the name, not a package.
                "set separator none",
                "legend top left"));
        header.addAll(legend);
        header.add("endlegend");
        return builder
                .withClassLoader(ClassDiagrams.class.getClassLoader())
                .withUseShortClassNames(false)
                .withUseShortClassNamesInFieldsAndMethods(true)
                .withJPAAnnotations(true)
                // All fields, so injected dependencies show up as relationships;
                // only public methods, minus accessors.
                .withMaximumFieldVisibility(VisibilityType.PRIVATE)
                .withMaximumMethodVisibility(VisibilityType.PUBLIC)
                .withMethodBlacklistRegexp(ACCESSORS)
                .addAdditionalPlantUmlConfigs(header)
                .build();
    }

    /**
     * Generates the diagram, adds {@code relationships} (PlantUML relationship
     * lines; one that only adds multiplicities to a line the generator drew
     * replaces it) and writes docs/class-diagrams/{level}.generated.puml.
     */
    static String generate(PlantUMLClassDiagramConfig config, String level, List<String> relationships)
            throws IOException {
        List<String> lines = tidy(new PlantUMLClassDiagramGenerator(config).generateDiagramText());
        List<String> added = new ArrayList<>();
        for (String relationship : relationships) {
            int drawn = lines.indexOf(relationship.replaceAll("\"[^\"]*\" ", ""));
            if (drawn >= 0) {
                lines.set(drawn, relationship);
            } else {
                added.add(relationship);
            }
        }
        if (!added.isEmpty()) {
            added.add("");
            lines.addAll(lines.lastIndexOf("@enduml"), added);
        }
        // "\n" on every OS, so CI's up-to-date check compares like with like.
        String diagram = String.join("\n", lines);
        Files.createDirectories(OUTPUT_DIR);
        Files.writeString(OUTPUT_DIR.resolve(level + ".generated.puml"), diagram, StandardCharsets.UTF_8);
        return diagram;
    }

    /**
     * Rewrites the generator's output into what the diagram should say:
     * <ul>
     * <li>short class names, nested ones as Outer.Inner</li>
     * <li>no JPA annotations or table names: {@code @Id} becomes {id}, and the
     * class keeps its {@literal <<Entity>>} stereotype</li>
     * <li>field types with their type arguments ({@code List<String>}, where
     * the generator writes {@code List})</li>
     * <li>no record accessors, which repeat the record's fields</li>
     * <li>plain associations instead of composition and aggregation (see
     * {@link #relationship})</li>
     * </ul>
     */
    private static List<String> tidy(String generated) {
        Map<String, String> shortNames = new HashMap<>();
        Map<String, String> declared = new HashMap<>();
        List<String> lines = new ArrayList<>();
        Class<?> current = null;
        boolean tableHeader = false;
        for (String line : generated.split("\\R", -1)) {
            Matcher declaration = DECLARATION.matcher(line);
            if (declaration.find()) {
                current = load(declaration.group(1));
                String shortName = displayName(current);
                String clash = declared.put(shortName, current.getName());
                if (clash != null) {
                    throw new IllegalStateException(
                            "Two classes would share the name %s: %s".formatted(shortName, clash));
                }
                lines.add(shortNames(line, shortNames).replace("<<Table>>  ", ""));
            } else if (line.strip().startsWith("{TableName=")) {
                tableHeader = true;
            } else if (tableHeader && line.equals("--")) {
                tableHeader = false;
            } else if (current != null && line.equals("}")) {
                current = null;
                lines.add(line);
            } else if (current != null && line.startsWith("\t{field}")) {
                lines.add(field(current, line));
            } else if (current != null && line.startsWith("\t{method}")) {
                Matcher method = NO_ARG_METHOD.matcher(line);
                if (!(current.isRecord() && method.matches() && isComponent(current, method.group(1)))) {
                    lines.add(line);
                }
            } else if (current == null && RELATIONSHIP.matcher(line).matches()) {
                lines.add(relationship(line, shortNames));
            } else {
                lines.add(line);
            }
        }
        return lines;
    }

    private static String field(Class<?> owner, String line) {
        boolean id = line.contains("@Id ");
        String plain = line.replaceAll(ANNOTATION.pattern() + " ", "");
        Matcher matcher = FIELD.matcher(plain);
        Field field = matcher.matches() ? declaredField(owner, matcher.group(2)) : null;
        if (field == null) {
            return plain;
        }
        return matcher.group(1) + field.getName() + " : " + typeName(field.getGenericType()) + (id ? " {id}" : "");
    }

    /**
     * The generator draws every final field as composition, which turns each
     * constructor-injected bean into an "owned part": a shared Spring bean is a
     * plain association. Composition is drawn only for an entity's embedded
     * id, which has no existence of its own (the generator draws that one as
     * a plain association). The generator draws every collection
     * field as aggregation with a "1" on the owner's end, which says each
     * element belongs to exactly one owner; that is wrong for a shared enum
     * value or entity, so it becomes an association with "0..*" on the element
     * end, which is all the field says.
     */
    private static String relationship(String line, Map<String, String> shortNames) {
        Matcher matcher = RELATIONSHIP.matcher(line);
        if (!matcher.matches() || !ARROWS.contains(matcher.group(3))) {
            return line;
        }
        String source = matcher.group(1);
        String sourceEnd = matcher.group(2);
        String arrow = matcher.group(3);
        String targetEnd = matcher.group(4);
        String target = matcher.group(5);
        // " :  @Column("status")\nstatus" -> " : status"
        String label = matcher.group(6).replaceAll(":\\s+" + ANNOTATION.pattern() + "\\\\n", ": ");
        if (arrow.equals("*--") || arrow.equals("-->")) {
            boolean embeddedId = load(source).isAnnotationPresent(Entity.class)
                    && load(target).isAnnotationPresent(Embeddable.class);
            arrow = embeddedId ? "*--" : "-->";
        } else if (arrow.equals("o--")) {
            arrow = "-->";
            sourceEnd = null;
        }
        String rebuilt = Stream.of(source, sourceEnd, arrow, targetEnd, target)
                .filter(Objects::nonNull).collect(Collectors.joining(" ")) + label;
        return shortNames(rebuilt, shortNames);
    }

    private static String shortNames(String line, Map<String, String> cache) {
        return QUALIFIED_NAME.matcher(line).replaceAll(match -> Matcher.quoteReplacement(
                cache.computeIfAbsent(match.group(), name -> displayName(load(name)))));
    }

    private static Class<?> load(String name) {
        try {
            return Class.forName(name, false, ClassDiagrams.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("The generator named a class that does not load: " + name, e);
        }
    }

    // OrganiserArrangementsDto.Venue for a nested type, Venue for a top-level one.
    private static String displayName(Class<?> type) {
        Class<?> outer = type.getEnclosingClass();
        return outer == null ? type.getSimpleName() : displayName(outer) + "." + type.getSimpleName();
    }

    private static String typeName(Type type) {
        if (type instanceof Class<?> c) {
            return c.isArray() ? typeName(c.getComponentType()) + "[]" : displayName(c);
        }
        if (type instanceof ParameterizedType p) {
            return typeName(p.getRawType()) + Arrays.stream(p.getActualTypeArguments())
                    .map(ClassDiagrams::typeName).collect(Collectors.joining(", ", "<", ">"));
        }
        if (type instanceof WildcardType w && w.getUpperBounds().length > 0
                && w.getUpperBounds()[0] != Object.class) {
            return "? extends " + typeName(w.getUpperBounds()[0]);
        }
        return type instanceof WildcardType ? "?" : type.getTypeName();
    }

    private static Field declaredField(Class<?> owner, String name) {
        for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
            try {
                return type.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                // keep looking in the superclass
            }
        }
        return null;
    }

    private static boolean isComponent(Class<?> record, String name) {
        return Arrays.stream(record.getRecordComponents()).map(RecordComponent::getName).anyMatch(name::equals);
    }
}
