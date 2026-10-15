package edu.gcu.cst323.cloudshop.logging;

import edu.gcu.cst323.cloudshop.exception.InsufficientStockException;
import edu.gcu.cst323.cloudshop.exception.ResourceNotFoundException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * The code that tags every exception line in the log, so that one search finds
 * every occurrence of a kind of failure whichever layer reported it.
 *
 * <p>Three of the four are expected: the shop is working as designed when a buyer
 * asks for more than is in stock, follows a stale link or mistypes a form. Those
 * are logged at WARN, on one line. {@link #UNEXPECTED} is everything else, and is
 * logged at ERROR with a stack trace.
 *
 * @author Dimitri Jimenez
 */
public enum ErrorCode {

    /** A purchase asked for more units than were left. */
    STOCK("ERR-STOCK", true),

    /** A request referred to something that does not exist. */
    NOT_FOUND("ERR-NOTFOUND", true),

    /** A request carried a value that failed binding or validation. */
    VALIDATION("ERR-VALIDATION", true),

    /** Anything the application did not anticipate. */
    UNEXPECTED("ERR-UNEXPECTED", false);

    /** The code as it is written in the log, for example {@code ERR-STOCK}. */
    private final String label;

    /** True for an ordinary business outcome, logged at WARN; false for a fault, logged at ERROR. */
    private final boolean expected;

    /**
     * Defines one code.
     *
     * @param label    the code as it is written in the log
     * @param expected true if the code marks an ordinary business outcome rather than a fault
     */
    ErrorCode(String label, boolean expected) {
        this.label = label;
        this.expected = expected;
    }

    /**
     * Returns the code as it is written in the log.
     *
     * @return the label, for example {@code ERR-STOCK}
     */
    public String label() {
        return label;
    }

    /**
     * Reports whether this is an ordinary business outcome rather than a fault.
     *
     * @return true for the codes logged at WARN on one line, false for the one
     *         logged at ERROR with a stack trace
     */
    public boolean isExpected() {
        return expected;
    }

    /**
     * Classifies an exception.
     *
     * @param ex the exception a layer has just seen
     * @return the code for it; {@link #UNEXPECTED} unless it is one of the known kinds
     */
    public static ErrorCode of(Throwable ex) {
        if (ex instanceof InsufficientStockException) {
            return STOCK;
        }
        // UsernameNotFoundException is how UserService must report "no such account"
        // to Spring Security. A mistyped name at sign-in is as ordinary as a stale
        // product link, so it is a not-found, not a fault.
        if (ex instanceof ResourceNotFoundException || ex instanceof UsernameNotFoundException) {
            return NOT_FOUND;
        }
        if (ex instanceof BindException
                || ex instanceof MethodArgumentTypeMismatchException
                || ex instanceof MissingServletRequestParameterException
                || ex instanceof HandlerMethodValidationException
                || ex instanceof ConstraintViolationException) {
            return VALIDATION;
        }
        return UNEXPECTED;
    }
}
