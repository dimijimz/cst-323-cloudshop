package edu.gcu.cst323.cloudshop.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.support.RequestContextUtils;

/**
 * Turns the application's own exceptions into pages a shopper can act on,
 * for every controller at once.
 *
 * <p>Anything not handled here falls through to Spring Boot's error handling,
 * which renders the same {@code error.html} template with the matching status.
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
        log.warn("404 {} {} - {}", request.getMethod(), request.getRequestURI(), ex.getMessage());

        ModelAndView view = new ModelAndView("error");
        view.setStatus(HttpStatus.NOT_FOUND);
        view.addObject("status", HttpStatus.NOT_FOUND.value());
        view.addObject("error", HttpStatus.NOT_FOUND.getReasonPhrase());
        view.addObject("message", ex.getMessage());
        return view;
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
        log.warn("Purchase rejected: product={} requested={} available={}",
                ex.getProductId(), ex.getRequested(), ex.getAvailable());

        // RedirectAttributes is not available to exception handlers, so the flash
        // message goes onto the output flash map directly; the redirect saves it.
        RequestContextUtils.getOutputFlashMap(request).put("error", ex.getMessage());
        return "redirect:/products/" + ex.getProductId();
    }
}
