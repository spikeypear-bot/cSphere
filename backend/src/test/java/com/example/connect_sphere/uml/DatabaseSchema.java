package com.example.connect_sphere.uml;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * DEV15: the Flyway-migrated schema as Postgres's catalog describes it, for
 * the domain class diagram's relationships ({@link DomainClassDiagramTest})
 * and the ER diagram ({@link EntityRelationshipDiagramTest}).
 */
final class DatabaseSchema {

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

    // Every column of every table, in the order the migrations created them.
    private static final String COLUMNS = """
            SELECT c.relname AS table_name, a.attname AS column_name,
                   format_type(a.atttypid, a.atttypmod) AS type, a.attnotnull AS not_null,
                   EXISTS (SELECT 1 FROM pg_constraint k WHERE k.conrelid = c.oid AND k.contype = 'p'
                             AND a.attnum = ANY (k.conkey)) AS primary_key,
                   EXISTS (SELECT 1 FROM pg_constraint k WHERE k.conrelid = c.oid AND k.contype = 'f'
                             AND a.attnum = ANY (k.conkey)) AS foreign_key
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
            WHERE c.relkind IN ('r', 'p') AND n.nspname = current_schema()
            ORDER BY c.relname, a.attnum
            """;

    private static final String ENUM_TYPES = """
            SELECT t.typname, e.enumlabel
            FROM pg_type t
            JOIN pg_enum e ON e.enumtypid = t.oid
            JOIN pg_namespace n ON n.oid = t.typnamespace
            WHERE n.nspname = current_schema()
            ORDER BY t.typname, e.enumsortorder
            """;

    record ForeignKey(String table, List<String> columns, String refTable, boolean notNull, boolean unique) {
    }

    record Column(String table, String name, String type, boolean notNull, boolean primaryKey,
            boolean foreignKey) {
    }

    private DatabaseSchema() {
    }

    static List<ForeignKey> foreignKeys(JdbcTemplate jdbc) {
        return jdbc.query(FOREIGN_KEYS, (rs, i) -> new ForeignKey(
                rs.getString("table_name"),
                List.of((String[]) rs.getArray("columns").getArray()),
                rs.getString("ref_table"),
                rs.getBoolean("not_null"),
                rs.getBoolean("is_unique")));
    }

    static List<Column> columns(JdbcTemplate jdbc) {
        return jdbc.query(COLUMNS, (rs, i) -> new Column(
                rs.getString("table_name"),
                rs.getString("column_name"),
                rs.getString("type"),
                rs.getBoolean("not_null"),
                rs.getBoolean("primary_key"),
                rs.getBoolean("foreign_key")));
    }

    /** Each Postgres enum type's values, in their declared order. */
    static Map<String, List<String>> enumTypes(JdbcTemplate jdbc) {
        Map<String, List<String>> types = new LinkedHashMap<>();
        jdbc.query(ENUM_TYPES, rs -> {
            types.computeIfAbsent(rs.getString("typname"), type -> new ArrayList<>())
                    .add(rs.getString("enumlabel"));
        });
        return types;
    }
}
