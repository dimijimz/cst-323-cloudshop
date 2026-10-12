package edu.gcu.cst323.cloudshop.exception;

import edu.gcu.cst323.cloudshop.logging.ExceptionLog;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.support.RequestContextUtils;

/**
 * Turns exceptions into pages a shopper can act on, for every controller at once,
 * and is the last layer to log them.
 *
 * <p>Each handler writes one line through {@link ExceptionLog}, so it carries the
 * same error code as the lines {@code LoggingAspect} wrote on the way up:
 * <pre>
 * HANDLED 404 GET /products/{id} [ERR-NOTFOUND] ResourceNotFoundException: Product 9 was not found
 * </pre>
 * The route is logged as its pattern, never as the address that was typed, because
 * an address can hold anything a visitor chose to put in it.
 *
 * <p>Errors Spring already knows how to answer, such as a 404 for an unknown
 * address, are left to Spring Boot's error handling, which renders the same
 * {@code error.html} template with the matching status.
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Shows the 404 page when a product or account cannot be found.
     *
     * @param ex      the exception naming what was missing
     * @param request the request that asked for it, for the log line
     * @return the error view with a 404 status
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ModelAndView handleNotFound(ResourceNotFoundException ex, HttpServletRequest request) {
        ExceptionLog.write(log, handled(HttpStatus.NOT_FOUND, request), ex);
        return errorPage(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    /**
     * Sends a buyer back to the product page when there was not enough stock.
     *
     * <p>A redirect rather than re-rendering in place, so the page the buyer lands
     * on shows the stock as it is now and refreshing it does not resubmit the order.
     * The message travels as a flash attribute and is shown once.
     *
     * @param ex      the exception carrying the product and the quantities involved
     * @param request the current request, used to attach the flash message
     * @return a redirect to the product's detail page
     */
    @ExceptionHandler(InsufficientStockException.class)
    public String handleInsufficientStock(InsufficientStockException ex, HttpServletRequest request) {
        ExceptionLog.write(log, handled(HttpStatus.FOUND, request), ex);

        // RedirectAttributes is not available to exception handlers, so the flash
        // message goes onto the output flash map directly; the redirect saves it.
        RequestContextUtils.getOutputFlashMap(request).put("error", ex.getMessage());
        return "redirect:/products/" + ex.getProductId();
    }

    /**
     * Shows a 400 page when a request carries a value that cannot be bound or does
     * not pass validation, such as letters where a product id belongs.
     *
     * <p>The page says only that a value was unusable. Spring's own message for
     * these repeats the value that was sent, which is not something to echo back
     * or to log.
     *
     * @param ex      the binding or validation failure
     * @param request the request that carried the value, for the log line
     * @return the error view with a 400 status
     */
    @ExceptionHandler({
            BindException.class,
            MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class,
            HandlerMethodValidationException.class,
            ConstraintViolationException.class
    })
    public ModelAndView handleInvalidRequest(Exception ex, HttpServletRequest request) {
        ExceptionLog.write(log, handled(HttpStatus.BAD_REQUEST, request), ex);
        return errorPage(HttpStatus.BAD_REQUEST,
                "That request had a value this page could not use. Check the address or the form and try again.");
    }

    /**
     * Shows a 500 page for anything no other handler claimed.
     *
     * <p>Without this the exception would travel on to the servlet container,
     * which prints its stack trace a second time. Handling it here keeps the trace
     * to the one copy written where the exception was first seen.
     *
     * <p>Two kinds of exception are passed on untouched by throwing them again,
     * which tells Spring to carry on as if this handler were not here: the ones
     * Spring MVC maps to a status itself, so an unknown address stays a 404, and
     * Spring Security's own, so a refusal still becomes a 403 or the login page.
     *
     * @param ex      the exception
     * @param request the request that failed, for the log line
     * @return the error view with a 500 status
     * @throws Exception the same exception, when it is one that Spring MVC or
     *                   Spring Security should answer instead
     */
    @ExceptionHandler(Exception.class)
    public ModelAndView handleUnexpected(Exception ex, HttpServletRequest request) throws Exception {
        if (ex instanceof ErrorResponse || ex instanceof AccessDeniedException || ex instanceof AuthenticationException) {
            throw ex;
        }
        ExceptionLog.write(log, handled(HttpStatus.INTERNAL_SERVER_ERROR, request), ex);
        return errorPage(HttpStatus.INTERNAL_SERVER_ERROR,
                "Something went wrong on our side. Please try again in a moment.");
    }

    private static String handled(HttpStatus status, HttpServletRequest request) {
        Object route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return "HANDLED " + status.value() + " " + request.getMethod() + " " + (route == null ? "(no route)" : route);
    }

    private static ModelAndView errorPage(HttpStatus status, String message) {
        ModelAndView view = new ModelAndView("error");
        view.setStatus(status);
        view.addObject("status", status.value());
        view.addObject("error", status.getReasonPhrase());
        view.addObject("message", message);
        return view;
    }
}
