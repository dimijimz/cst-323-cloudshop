package edu.gcu.cst323.cloudshop.logging;

import org.slf4j.Logger;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Writes the entry a layer makes when it sees an exception, and makes sure each
 * exception's stack trace is written once however many layers it passes through.
 *
 * <p>An exception raised in a repository is seen again by the service that called
 * it, the controller above that and the exception handler above that. Each of them
 * logs one line, so the path the failure took is readable from the log. Only the
 * first of them attaches the stack trace: four copies of it would bury the lines
 * around them and say nothing new.
 *
 * <p>Both {@link LoggingAspect} and {@code GlobalExceptionHandler} log through
 * here, which is what keeps their codes and wording the same.
 */
public final class ExceptionLog {

    /** A cause chain longer than this is not walked further; it also bounds a cyclic one. */
    private static final int MAX_CAUSE_DEPTH = 20;

    /**
     * The unexpected exceptions whose stack trace has already been written. Keys
     * are held weakly, so an entry disappears with its exception and nothing has
     * to be cleaned up when a request ends.
     */
    private static final Map<Throwable, Boolean> TRACED = Collections.synchronizedMap(new WeakHashMap<>());

    private ExceptionLog() {
    }

    /**
     * Logs one entry of the form {@code context [CODE] Type: message}.
     *
     * <p>An expected business exception is logged at WARN. Anything else is logged
     * at ERROR, with its stack trace the first time it is seen and without it after
     * that.
     *
     * @param log     the logger of the class that saw the exception
     * @param context how this layer met it, for example
     *                {@code EXCEPTION PurchaseService.purchase}
     * @param ex      the exception
     */
    public static void write(Logger log, String context, Throwable ex) {
        ErrorCode code = ErrorCode.of(ex);
        String description = LogValues.exception(ex);

        if (code.isExpected()) {
            log.warn("{} [{}] {}", context, code.label(), description);
        } else if (firstSighting(ex)) {
            // The trailing argument has no placeholder, so SLF4J prints it as the stack trace.
            log.error("{} [{}] {}", context, code.label(), description, ex);
        } else {
            log.error("{} [{}] {}", context, code.label(), description);
        }
    }

    /**
     * Records that this exception's stack trace is about to be written, unless it
     * already has been. An exception that wraps one already written counts as
     * written too: the frames that matter are the cause's, and they are in the log.
     */
    private static boolean firstSighting(Throwable ex) {
        Throwable current = ex;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (TRACED.containsKey(current)) {
                return false;
            }
            current = current.getCause();
        }
        TRACED.put(ex, Boolean.TRUE);
        return true;
    }
}
