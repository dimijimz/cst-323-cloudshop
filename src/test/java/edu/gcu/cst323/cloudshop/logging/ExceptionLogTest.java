package edu.gcu.cst323.cloudshop.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import edu.gcu.cst323.cloudshop.exception.InsufficientStockException;
import edu.gcu.cst323.cloudshop.exception.ResourceNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The level of an exception entry, and the once-only stack trace. No Spring
 * context: a logger, and exceptions passed to it the way successive layers would.
 */
class ExceptionLogTest {

    private static final Logger REPOSITORY = LoggerFactory.getLogger(LogCapture.APPLICATION_LOGGER + ".test.Repository");
    private static final Logger SERVICE = LoggerFactory.getLogger(LogCapture.APPLICATION_LOGGER + ".test.Service");

    private LogCapture log;

    @BeforeEach
    void setUp() {
        log = LogCapture.start(Level.INFO);
    }

    @AfterEach
    void tearDown() {
        log.close();
    }

    @Test
    @DisplayName("an expected business exception is a WARN line with its code and no stack trace, at every layer")
    void expectedExceptionsAreWarnings() {
        InsufficientStockException noStock = new InsufficientStockException(3L, "Webcam", 5, 1);
        ResourceNotFoundException missing = new ResourceNotFoundException("Product", 9L);

        ExceptionLog.write(SERVICE, "EXCEPTION Service.purchase", noStock);
        ExceptionLog.write(SERVICE, "EXCEPTION Service.find", missing);
        ExceptionLog.write(SERVICE, "EXCEPTION Service.find", missing);

        assertThat(log.lines()).containsExactly(
                "WARN Service - EXCEPTION Service.purchase [ERR-STOCK] InsufficientStockException: Only 1 of Webcam "
                        + "left in stock, so an order for 5 could not be placed. Nothing was purchased.",
                "WARN Service - EXCEPTION Service.find [ERR-NOTFOUND] ResourceNotFoundException: Product 9 was not found",
                "WARN Service - EXCEPTION Service.find [ERR-NOTFOUND] ResourceNotFoundException: Product 9 was not found");
        assertThat(log.eventsWithStackTrace()).isEmpty();
    }

    @Test
    @DisplayName("an unexpected exception is an ERROR with its stack trace the first time, and an ERROR line alone every time after")
    void unexpectedExceptionIsTracedOnce() {
        IllegalStateException failure = new IllegalStateException("boom");

        ExceptionLog.write(REPOSITORY, "EXCEPTION Repository.save", failure);
        ExceptionLog.write(SERVICE, "EXCEPTION Service.create", failure);
        ExceptionLog.write(SERVICE, "HANDLED 500 POST /things", failure);

        assertThat(log.lines()).containsExactly(
                "ERROR Repository - EXCEPTION Repository.save [ERR-UNEXPECTED] IllegalStateException: boom",
                "ERROR Service - EXCEPTION Service.create [ERR-UNEXPECTED] IllegalStateException: boom",
                "ERROR Service - HANDLED 500 POST /things [ERR-UNEXPECTED] IllegalStateException: boom");

        List<ILoggingEvent> traced = log.eventsWithStackTrace();
        assertThat(traced).singleElement().satisfies(event -> {
            assertThat(event.getLoggerName()).isEqualTo(REPOSITORY.getName());
            assertThat(event.getThrowableProxy().getClassName()).isEqualTo(IllegalStateException.class.getName());
            assertThat(event.getThrowableProxy().getMessage()).isEqualTo("boom");
        });
    }

    @Test
    @DisplayName("an exception that wraps one already traced is not traced again")
    void wrapperOfATracedExceptionIsNotTracedAgain() {
        IllegalStateException cause = new IllegalStateException("the real failure");
        ExceptionLog.write(REPOSITORY, "EXCEPTION Repository.save", cause);

        RuntimeException wrapper = new RuntimeException("could not save", cause);
        ExceptionLog.write(SERVICE, "EXCEPTION Service.create", wrapper);

        assertThat(log.lines()).containsExactly(
                "ERROR Repository - EXCEPTION Repository.save [ERR-UNEXPECTED] IllegalStateException: the real failure",
                "ERROR Service - EXCEPTION Service.create [ERR-UNEXPECTED] RuntimeException: could not save");
        assertThat(log.eventsWithStackTrace()).hasSize(1);
    }

    @Test
    @DisplayName("two different exceptions each get their own stack trace, even with the same type and message")
    void separateExceptionsAreTracedSeparately() {
        ExceptionLog.write(SERVICE, "EXCEPTION Service.create", new IllegalStateException("boom"));
        ExceptionLog.write(SERVICE, "EXCEPTION Service.create", new IllegalStateException("boom"));

        assertThat(log.eventsWithStackTrace()).hasSize(2);
    }

    @Test
    @DisplayName("braces in an exception message are logged as written, not treated as placeholders")
    void messageBracesAreNotPlaceholders() {
        ExceptionLog.write(SERVICE, "EXCEPTION Service.parse", new ResourceNotFoundException("No template named {}"));

        assertThat(log.lines()).containsExactly(
                "WARN Service - EXCEPTION Service.parse [ERR-NOTFOUND] ResourceNotFoundException: No template named {}");
    }
}
