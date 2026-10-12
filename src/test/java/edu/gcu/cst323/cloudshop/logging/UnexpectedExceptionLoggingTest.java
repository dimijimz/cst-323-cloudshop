package edu.gcu.cst323.cloudshop.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import edu.gcu.cst323.cloudshop.repository.ProductRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Follows one genuine failure from the database up through the repository, the
 * service, the controller and the exception handler, and checks that its stack
 * trace is written exactly once.
 *
 * <p>The failure is real: a column the catalog query needs is renamed for the
 * length of one request, so the database itself rejects the query. The request is
 * made over HTTP to a real embedded Tomcat rather than through MockMvc, because
 * the second copy of the trace this guards against is the one the servlet
 * container prints when an exception gets as far as it.
 *
 * <p>Runs against its own in-memory database, so the renamed column cannot be seen
 * by any other test class.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties =
        "spring.datasource.url=jdbc:h2:mem:unexpectedlogging;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles({"h2", "test"})
@ExtendWith(OutputCaptureExtension.class)
class UnexpectedExceptionLoggingTest {

    private static final String CODE = "[ERR-UNEXPECTED] ";

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("a failure that crosses every layer is one ERROR line per layer, with the stack trace on the first only, and a 500 page that gives nothing away")
    void stackTraceIsWrittenOnceForAFailureThatCrossesEveryLayer(CapturedOutput output) {
        ResponseEntity<String> response;
        List<String> lines;
        List<ILoggingEvent> traced;

        // INFO, the default: none of this depends on DEBUG being switched on.
        try (LogCapture log = LogCapture.start(Level.INFO)) {
            jdbc.execute("ALTER TABLE products ALTER COLUMN active RENAME TO active_renamed");
            try {
                response = http.getForEntity("/products", String.class);
            } finally {
                jdbc.execute("ALTER TABLE products ALTER COLUMN active_renamed RENAME TO active");
            }
            lines = log.lines();
            traced = log.eventsWithStackTrace();
        }

        // The visitor gets the shop's own error page, with no SQL or class names on it.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody())
                .contains("Something went wrong on our side")
                .doesNotContain("Exception", "active_renamed", "select ");

        // One line per layer, innermost first, all at ERROR, all with the same code.
        assertThat(lines).hasSize(4);
        assertThat(lines.get(0)).startsWith(
                "ERROR ProductRepository - EXCEPTION ProductRepository.findByActiveTrueOrderByNameAsc " + CODE);
        assertThat(lines.get(1)).startsWith("ERROR ProductService - EXCEPTION ProductService.findCatalog " + CODE);
        assertThat(lines.get(2)).startsWith("ERROR CatalogController - EXCEPTION CatalogController.list " + CODE);
        assertThat(lines.get(3)).startsWith("ERROR GlobalExceptionHandler - HANDLED 500 GET /products " + CODE);
        // ... and the same "Type: message", on a single line each, though the
        // database's own message spans several.
        String description = lines.get(0).substring(lines.get(0).indexOf(CODE) + CODE.length());
        assertThat(description).startsWith("InvalidDataAccessResourceUsageException: ").doesNotContain("\n");
        assertThat(lines).allMatch(line -> line.endsWith(CODE + description));

        // The stack trace: once, on the repository's line, where the exception was
        // first seen. Nothing else in the JVM, the servlet container included, logged it.
        assertThat(traced).singleElement().satisfies(event -> {
            assertThat(event.getLoggerName()).isEqualTo(ProductRepository.class.getName());
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
        });
        String tracedType = traced.get(0).getThrowableProxy().getClassName();
        assertThat(output.getOut().lines().filter(line -> line.startsWith(tracedType + ":")))
                .as("stack traces for %s on the console", tracedType)
                .hasSize(1);
        assertThat(output.getOut()).contains("\tat ").doesNotContain("Servlet.service()");

        // With the column back, the shop works again.
        assertThat(http.getForEntity("/products", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
