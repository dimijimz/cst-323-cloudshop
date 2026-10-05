package edu.gcu.cst323.cloudshop;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Guards the documentation exports in docs/ddl against drifting away from the
 * Flyway migrations they were exported from.
 *
 * <p>The exports exist because the design report shows the DDL script, but the
 * migrations remain the source of truth: the application builds its own schema
 * at startup. A copy of SQL that nothing ever executes is exactly the kind of
 * file that rots quietly - someone adds V3, the export still describes the old
 * schema, and the documentation now misrepresents the deployed database.
 *
 * <p>This is a plain unit test with no Spring context and no database, so it
 * costs milliseconds and fails the build anywhere `mvn package` runs, including
 * the Heroku buildpack.
 *
 * <p>Comparison is by executable SQL: comments and whitespace are stripped from
 * both sides first, because the exports deliberately carry a header the
 * migrations do not. Everything the database would actually execute has to
 * match, statement for statement.
 */
class DdlExportSyncTest {

    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");
    private static final Path EXPORTS = Path.of("docs/ddl");

    @Test
    @DisplayName("docs/ddl/schema.sql still matches the V1 migration")
    void schemaExportMatchesV1Migration() {
        assertExportInSync("V1__create_schema.sql", "schema.sql");
    }

    @Test
    @DisplayName("docs/ddl/seed.sql still matches the V2 migration")
    void seedExportMatchesV2Migration() {
        assertExportInSync("V2__seed_data.sql", "seed.sql");
    }

    @Test
    @DisplayName("every migration has an export, so adding V3 cannot silently skip it")
    void everyMigrationHasAnExport() {
        List<String> unexported = new ArrayList<>();
        try (Stream<Path> files = Files.list(projectRoot().resolve(MIGRATIONS))) {
            files.map(p -> p.getFileName().toString())
                    .filter(name -> name.endsWith(".sql"))
                    .filter(name -> exportFor(name) == null)
                    .sorted()
                    .forEach(unexported::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        if (!unexported.isEmpty()) {
            fail("Migration(s) with no documentation export in docs/ddl: " + unexported
                    + "\n\nA migration was added without exporting it. Either add the export and map it"
                    + "\nin DdlExportSyncTest#exportFor, or decide no export is needed and record that"
                    + "\ndecision there - but do not leave docs/ddl describing a schema the application"
                    + "\nno longer creates.");
        }
    }

    @Test
    @DisplayName("exports keep the header marking them as documentation, not a deploy script")
    void exportsCarryTheDocumentationHeader() {
        assertHeader("schema.sql", "V1__create_schema.sql");
        assertHeader("seed.sql", "V2__seed_data.sql");
    }

    // --- helpers ------------------------------------------------------------

    /** Maps a migration file name to its export, or null when no export is expected. */
    private static String exportFor(String migrationFileName) {
        return switch (migrationFileName) {
            case "V1__create_schema.sql" -> "schema.sql";
            case "V2__seed_data.sql" -> "seed.sql";
            default -> null;
        };
    }

    private void assertHeader(String exportFileName, String sourceMigration) {
        String text = read(projectRoot().resolve(EXPORTS).resolve(exportFileName));
        String header = text.substring(0, Math.min(text.length(), 2500)).toLowerCase(Locale.ROOT);

        assertTrue(header.contains("source of truth"),
                exportFileName + " lost the line naming the migrations as the source of truth."
                        + " Without it, the next reader cannot tell this file is not the thing that runs.");
        assertTrue(header.contains(sourceMigration.toLowerCase(Locale.ROOT)),
                exportFileName + " no longer names the migration it was exported from ("
                        + sourceMigration + ").");
        assertTrue(header.contains("documentation export"),
                exportFileName + " no longer identifies itself as a documentation export.");
        assertTrue(header.contains("run by hand"),
                exportFileName + " lost the warning against running it by hand against a cloud database."
                        + " That warning is load-bearing: Flyway runs with baseline-on-migrate=false, so a"
                        + " manual run leaves the next startup refusing to boot.");
    }

    private void assertExportInSync(String migrationFileName, String exportFileName) {
        List<String> expected = statements(read(projectRoot().resolve(MIGRATIONS).resolve(migrationFileName)));
        List<String> actual = statements(read(projectRoot().resolve(EXPORTS).resolve(exportFileName)));

        String hint = "\n\ndocs/ddl/" + exportFileName + " is out of sync with " + migrationFileName + "."
                + "\n\nThe exports are documentation only - the migrations are what actually runs - so the"
                + "\nfix is to re-export, never to edit the migration to match the export. Copy the SQL"
                + "\nfrom the migration into the export, keeping the export's header. Comments and"
                + "\nwhitespace are ignored by this check; only executable SQL matters.\n";

        for (int i = 0; i < Math.min(expected.size(), actual.size()); i++) {
            assertEquals(expected.get(i), actual.get(i),
                    hint + "\nFirst difference is at statement " + (i + 1) + ":");
        }
        assertEquals(expected.size(), actual.size(), hint
                + "\nThe files do not contain the same number of statements: "
                + migrationFileName + " has " + expected.size() + ", "
                + exportFileName + " has " + actual.size() + ".");
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + path.toAbsolutePath(), e);
        }
    }

    /**
     * Walks up from the working directory to the directory holding pom.xml, so paths
     * resolve correctly no matter where the runner was launched from.
     */
    private static Path projectRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("pom.xml"))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException("Could not locate the project root: no pom.xml at or above "
                    + Path.of("").toAbsolutePath());
        }
        return dir;
    }

    /**
     * Splits SQL into executable statements, discarding comments and collapsing
     * whitespace. Quoted text is copied through untouched, so a comment marker or a
     * semicolon inside a string literal cannot corrupt the split. That matters: a
     * check that silently mangles its own input would report "in sync" for files
     * that are not.
     */
    private static List<String> statements(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int i = 0;
        int n = sql.length();

        while (i < n) {
            char c = sql.charAt(i);

            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {          // -- line comment
                while (i < n && sql.charAt(i) != '\n') i++;
                current.append(' ');
            } else if (c == '#') {                                            // # line comment
                while (i < n && sql.charAt(i) != '\n') i++;
                current.append(' ');
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {   // block comment
                i += 2;
                while (i + 1 < n && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) i++;
                i = Math.min(i + 2, n);
                current.append(' ');
            } else if (c == '\'' || c == '"' || c == '`') {                   // quoted literal or identifier
                char quote = c;
                current.append(c);
                i++;
                while (i < n) {
                    char d = sql.charAt(i);
                    if (d == '\\' && i + 1 < n) {                             // backslash escape
                        current.append(d).append(sql.charAt(i + 1));
                        i += 2;
                        continue;
                    }
                    current.append(d);
                    i++;
                    if (d == quote) {
                        if (i < n && sql.charAt(i) == quote) {                // doubled-quote escape
                            current.append(quote);
                            i++;
                            continue;
                        }
                        break;
                    }
                }
            } else if (c == ';') {
                addIfNotBlank(statements, current);
                current.setLength(0);
                i++;
            } else {
                current.append(c);
                i++;
            }
        }
        addIfNotBlank(statements, current);
        return statements;
    }

    private static void addIfNotBlank(List<String> statements, StringBuilder current) {
        String normalized = current.toString().replaceAll("\\s+", " ").trim();
        if (!normalized.isEmpty()) {
            statements.add(normalized);
        }
    }
}
