package edu.gcu.cst323.cloudshop.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Collects what the application logs while a test runs, so the test can assert on
 * the lines themselves.
 *
 * <p>It listens on the root logger, so it sees every event the console appender
 * sees, from every logger. It also sets the application's own log level for the
 * duration, and restores it afterwards. Setting it here rather than through a test
 * property matters: the Logback configuration is shared by the whole test JVM, so
 * the level left behind by whichever application context started last would
 * otherwise decide whether these tests see any DEBUG lines.
 */
final class LogCapture implements AutoCloseable {

    static final String APPLICATION_LOGGER = "edu.gcu.cst323.cloudshop";

    private static final Pattern DURATION = Pattern.compile("\\[\\d+ ms]");

    private final Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    private final Logger application = (Logger) LoggerFactory.getLogger(APPLICATION_LOGGER);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Level levelBefore;

    private LogCapture(Level level) {
        levelBefore = application.getLevel();
        application.setLevel(level);
        appender.start();
        root.addAppender(appender);
    }

    /**
     * Starts collecting, with the application's loggers at the given level.
     *
     * @param level the level APP_LOG_LEVEL would set
     * @return the capture; close it to stop collecting and restore the level
     */
    static LogCapture start(Level level) {
        return new LogCapture(level);
    }

    /** Changes the application's level part-way through a test. */
    void setLevel(Level level) {
        application.setLevel(level);
    }

    /** Every event collected so far, from any logger. */
    List<ILoggingEvent> events() {
        return List.copyOf(appender.list);
    }

    /**
     * The application's own events as {@code LEVEL LoggerClass - message}, which
     * shows both the level and whose logger wrote the line. Durations are replaced
     * with {@code [N ms]} so a line can be compared exactly.
     */
    List<String> lines() {
        return events().stream()
                .filter(event -> event.getLoggerName().startsWith(APPLICATION_LOGGER))
                .map(event -> event.getLevel() + " " + simpleName(event.getLoggerName()) + " - "
                        + DURATION.matcher(event.getFormattedMessage()).replaceAll("[N ms]"))
                .toList();
    }

    /** The events that carry a stack trace. */
    List<ILoggingEvent> eventsWithStackTrace() {
        return events().stream().filter(event -> event.getThrowableProxy() != null).toList();
    }

    /**
     * Everything that reached the log as one string: each message, and the type and
     * message of each exception in each stack trace. For asserting that a value
     * appears nowhere.
     */
    String everything() {
        StringBuilder all = new StringBuilder();
        for (ILoggingEvent event : events()) {
            all.append(event.getLoggerName()).append(' ').append(event.getFormattedMessage()).append('\n');
            append(all, event.getThrowableProxy(), 0);
        }
        return all.toString();
    }

    @Override
    public void close() {
        root.detachAppender(appender);
        appender.stop();
        application.setLevel(levelBefore);
    }

    private static void append(StringBuilder all, IThrowableProxy thrown, int depth) {
        if (thrown == null || depth > 20) {
            return;
        }
        all.append(thrown.getClassName()).append(": ").append(thrown.getMessage()).append('\n');
        append(all, thrown.getCause(), depth + 1);
        if (thrown.getSuppressed() != null) {
            for (IThrowableProxy suppressed : thrown.getSuppressed()) {
                append(all, suppressed, depth + 1);
            }
        }
    }

    private static String simpleName(String loggerName) {
        return loggerName.substring(loggerName.lastIndexOf('.') + 1);
    }
}
