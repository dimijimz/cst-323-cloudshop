package edu.gcu.cst323.cloudshop.logging;

import edu.gcu.cst323.cloudshop.controller.CatalogController;
import edu.gcu.cst323.cloudshop.exception.GlobalExceptionHandler;
import edu.gcu.cst323.cloudshop.exception.InsufficientStockException;
import edu.gcu.cst323.cloudshop.exception.ResourceNotFoundException;
import edu.gcu.cst323.cloudshop.form.RegistrationForm;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.ui.Model;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** The mapping from exception to error code, and which codes count as expected. */
class ErrorCodeTest {

    @Test
    @DisplayName("the four codes are spelled exactly as they are searched for")
    void labels() {
        assertThat(ErrorCode.values()).extracting(ErrorCode::label)
                .containsExactly("ERR-STOCK", "ERR-NOTFOUND", "ERR-VALIDATION", "ERR-UNEXPECTED");
    }

    @Test
    @DisplayName("only ERR-UNEXPECTED is a fault; the other three are ordinary business outcomes")
    void expectedCodes() {
        assertThat(ErrorCode.STOCK.isExpected()).isTrue();
        assertThat(ErrorCode.NOT_FOUND.isExpected()).isTrue();
        assertThat(ErrorCode.VALIDATION.isExpected()).isTrue();
        assertThat(ErrorCode.UNEXPECTED.isExpected()).isFalse();
    }

    @Test
    @DisplayName("the application's own exceptions map to their codes")
    void businessExceptions() {
        assertThat(ErrorCode.of(new InsufficientStockException(1L, "Webcam", 5, 1))).isEqualTo(ErrorCode.STOCK);
        assertThat(ErrorCode.of(new ResourceNotFoundException("Product", 9L))).isEqualTo(ErrorCode.NOT_FOUND);
        // Spring Security's "no such account", which UserService has to throw at sign-in.
        assertThat(ErrorCode.of(new UsernameNotFoundException("No account has that username")))
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("every binding and validation failure maps to ERR-VALIDATION")
    void validationExceptions() throws Exception {
        for (Exception failure : validationFailures()) {
            assertThat(ErrorCode.of(failure)).as(failure.getClass().getSimpleName()).isEqualTo(ErrorCode.VALIDATION);
        }
    }

    @Test
    @DisplayName("the exception handler's 400 page claims exactly the exceptions that map to ERR-VALIDATION")
    void validationHandlerAndCodeAgree() throws Exception {
        Class<?>[] handled = GlobalExceptionHandler.class
                .getMethod("handleInvalidRequest", Exception.class, HttpServletRequest.class)
                .getAnnotation(ExceptionHandler.class)
                .value();

        // Each failure below is claimed by the handler...
        for (Exception failure : validationFailures()) {
            assertThat(handled).as("handler for %s", failure.getClass().getSimpleName())
                    .anyMatch(type -> type.isInstance(failure));
        }
        // ... and the handler claims nothing that the list below does not cover.
        for (Class<?> type : handled) {
            assertThat(validationFailures()).as("example of %s", type.getSimpleName()).anyMatch(type::isInstance);
        }
    }

    @Test
    @DisplayName("everything else is ERR-UNEXPECTED")
    void everythingElse() {
        assertThat(ErrorCode.of(new IllegalArgumentException("Quantity must be at least 1"))).isEqualTo(ErrorCode.UNEXPECTED);
        assertThat(ErrorCode.of(new IllegalStateException())).isEqualTo(ErrorCode.UNEXPECTED);
        assertThat(ErrorCode.of(new NullPointerException())).isEqualTo(ErrorCode.UNEXPECTED);
        assertThat(ErrorCode.of(new DataIntegrityViolationException("duplicate"))).isEqualTo(ErrorCode.UNEXPECTED);
        assertThat(ErrorCode.of(new OutOfMemoryError())).isEqualTo(ErrorCode.UNEXPECTED);
        // Wrapping an expected exception does not make the wrapper expected.
        assertThat(ErrorCode.of(new RuntimeException(new ResourceNotFoundException("Product", 9L))))
                .isEqualTo(ErrorCode.UNEXPECTED);
    }

    /** One instance of each kind of binding or validation failure Spring MVC and Bean Validation raise. */
    private static List<Exception> validationFailures() throws Exception {
        MethodParameter id = new MethodParameter(
                CatalogController.class.getMethod("detail", Long.class, Model.class), 0);
        BindException binding = new BindException(new RegistrationForm(), "registrationForm");
        return List.of(
                binding,
                new MethodArgumentNotValidException(id, binding),
                new MethodArgumentTypeMismatchException("abc", Long.class, "id", id, null),
                new MissingServletRequestParameterException("productId", "Long"),
                mock(HandlerMethodValidationException.class),
                new ConstraintViolationException(Set.of()));
    }
}
