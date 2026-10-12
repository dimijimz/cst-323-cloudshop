package edu.gcu.cst323.cloudshop.logging;

import edu.gcu.cst323.cloudshop.controller.CatalogController;
import edu.gcu.cst323.cloudshop.exception.ResourceNotFoundException;
import edu.gcu.cst323.cloudshop.form.RegistrationForm;
import edu.gcu.cst323.cloudshop.model.Product;
import edu.gcu.cst323.cloudshop.model.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.TypeMismatchException;
import org.springframework.core.MethodParameter;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;
import org.springframework.validation.BindException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rules for what may be said about a value, one case at a time. No Spring
 * context: these are plain static methods.
 */
class LogValuesTest {

    private static final String SECRET = "s3cret-value-canary";

    // --- arguments ----------------------------------------------------------

    @Test
    @DisplayName("numbers, booleans, enums and dates are printed")
    void safeKindsArePrinted() {
        assertThat(LogValues.argument(Long.class, 42L)).isEqualTo("42");
        assertThat(LogValues.argument(int.class, 3)).isEqualTo("3");
        assertThat(LogValues.argument(BigDecimal.class, new BigDecimal("79.99"))).isEqualTo("79.99");
        assertThat(LogValues.argument(boolean.class, true)).isEqualTo("true");
        assertThat(LogValues.argument(Role.class, Role.OWNER)).isEqualTo("OWNER");
        assertThat(LogValues.argument(LocalDate.class, LocalDate.of(2026, 1, 31))).isEqualTo("2026-01-31");
        assertThat(LogValues.argument(LocalDateTime.class, LocalDateTime.of(2026, 1, 31, 9, 30))).isEqualTo("2026-01-31T09:30");
        assertThat(LogValues.argument(Instant.class, Instant.parse("2026-01-31T09:30:00Z"))).isEqualTo("2026-01-31T09:30:00Z");
        assertThat(LogValues.argument(Date.class, Date.from(Instant.parse("2026-01-31T09:30:00Z")))).isEqualTo("2026-01-31T09:30:00Z");
        // Decided by the value, so a number arriving through an untyped parameter is still printed.
        assertThat(LogValues.argument(Object.class, 7L)).isEqualTo("7");
    }

    @Test
    @DisplayName("a missing argument is printed as null")
    void nullIsPrinted() {
        assertThat(LogValues.argument(String.class, null)).isEqualTo("null");
        assertThat(LogValues.argument(Long.class, null)).isEqualTo("null");
    }

    @Test
    @DisplayName("a string is never printed, whatever the parameter is called or holds")
    void stringsAreReducedToTheirType() {
        assertThat(LogValues.argument(String.class, SECRET)).isEqualTo("<String>");
        assertThat(LogValues.argument(String.class, "")).isEqualTo("<String>");
        assertThat(LogValues.argument(String.class, "42")).isEqualTo("<String>");
        assertThat(LogValues.argument(CharSequence.class, new StringBuilder(SECRET))).isEqualTo("<CharSequence>");
        assertThat(LogValues.argument(char[].class, SECRET.toCharArray())).isEqualTo("<char[] length=19>");
        assertThat(LogValues.argument(Object.class, SECRET)).isEqualTo("<String>");
    }

    @Test
    @DisplayName("an object is reduced to its type: the declared one, or its own class when the declaration says nothing")
    void objectsAreReducedToTheirType() {
        RegistrationForm form = new RegistrationForm();
        form.setUsername(SECRET);
        form.setPassword(SECRET);
        Product product = new Product(SECRET, SECRET, new BigDecimal("1.00"), 1);

        assertThat(LogValues.argument(RegistrationForm.class, form)).isEqualTo("<RegistrationForm>");
        assertThat(LogValues.argument(Product.class, product)).isEqualTo("<Product>");
        // Spring Data's save(S entity) erases to Object, so the value's class is used.
        assertThat(LogValues.argument(Object.class, product)).isEqualTo("<Product>");
        // A Model is a map underneath, but it was declared as a Model.
        assertThat(LogValues.argument(Model.class, new ExtendedModelMap().addAttribute("k", SECRET))).isEqualTo("<Model>");
    }

    @Test
    @DisplayName("a collection, map, array or Optional is described by kind and size, never by content")
    void containersAreDescribedBySize() {
        assertThat(LogValues.argument(List.class, List.of(SECRET, SECRET))).isEqualTo("<List size=2>");
        assertThat(LogValues.argument(Set.class, Set.of(SECRET))).isEqualTo("<Set size=1>");
        assertThat(LogValues.argument(Iterable.class, List.of(1, 2, 3))).isEqualTo("<List size=3>");
        assertThat(LogValues.argument(Map.class, Map.of("password", SECRET))).isEqualTo("<Map size=1>");
        assertThat(LogValues.argument(String[].class, new String[] {SECRET, SECRET})).isEqualTo("<String[] length=2>");
        assertThat(LogValues.argument(Optional.class, Optional.of(SECRET))).isEqualTo("<Optional of String>");
        assertThat(LogValues.argument(Optional.class, Optional.empty())).isEqualTo("<Optional empty>");
        // Even a list of numbers: only a value passed directly is printed.
        assertThat(LogValues.argument(List.class, List.of(1L, 2L))).isEqualTo("<List size=2>");
    }

    @Test
    @DisplayName("a number or date type from outside the JDK is not trusted to print itself")
    void onlyJdkNumbersArePrinted() {
        Number leaky = new Number() {
            @Override public int intValue() { return 1; }
            @Override public long longValue() { return 1; }
            @Override public float floatValue() { return 1; }
            @Override public double doubleValue() { return 1; }
            @Override public String toString() { return SECRET; }
        };

        assertThat(LogValues.argument(Number.class, leaky)).isEqualTo("<Number>").doesNotContain(SECRET);
    }

    // --- results ------------------------------------------------------------

    @Test
    @DisplayName("a result is its type and nothing else, even when it is a number or a boolean")
    void resultsAreTypesOnly() {
        assertThat(LogValues.result(void.class, null)).isEqualTo("void");
        assertThat(LogValues.result(String.class, null)).isEqualTo("null");
        assertThat(LogValues.result(String.class, "redirect:/my-purchases")).isEqualTo("<String>");
        assertThat(LogValues.result(long.class, 5L)).isEqualTo("<long>");
        assertThat(LogValues.result(int.class, 1)).isEqualTo("<int>");
        assertThat(LogValues.result(boolean.class, true)).isEqualTo("<boolean>");
        assertThat(LogValues.result(BigDecimal.class, new BigDecimal("209.93"))).isEqualTo("<BigDecimal>");
        assertThat(LogValues.result(Product.class, new Product("n", "d", BigDecimal.ONE, 1))).isEqualTo("<Product>");
        // A type variable erased to Object: the value's own class.
        assertThat(LogValues.result(Object.class, new Product("n", "d", BigDecimal.ONE, 1))).isEqualTo("<Product>");
    }

    @Test
    @DisplayName("a collection result carries its size, and an Optional says whether it holds anything")
    void containerResultsCarryTheirSize() {
        assertThat(LogValues.result(List.class, List.of(1, 2, 3))).isEqualTo("<List size=3>");
        assertThat(LogValues.result(List.class, List.of())).isEqualTo("<List size=0>");
        assertThat(LogValues.result(Set.class, Set.of("a"))).isEqualTo("<Set size=1>");
        assertThat(LogValues.result(Map.class, Map.of("status", "UP"))).isEqualTo("<Map size=1>");
        assertThat(LogValues.result(Optional.class, Optional.of(new Product("n", "d", BigDecimal.ONE, 1))))
                .isEqualTo("<Optional of Product>");
        assertThat(LogValues.result(Optional.class, Optional.empty())).isEqualTo("<Optional empty>");
    }

    // --- exceptions ---------------------------------------------------------

    @Test
    @DisplayName("an exception is described as Type: message, on one line")
    void exceptionsAreDescribedOnOneLine() {
        assertThat(LogValues.exception(new ResourceNotFoundException("Product", 9L)))
                .isEqualTo("ResourceNotFoundException: Product 9 was not found");
        assertThat(LogValues.exception(new IllegalStateException()))
                .isEqualTo("IllegalStateException: (no message)");
        assertThat(LogValues.exception(new IllegalStateException("first line\r\n   second line\nthird")))
                .isEqualTo("IllegalStateException: first line second line third");
    }

    @Test
    @DisplayName("a binding failure is summarised by field name; the rejected value is not repeated")
    void bindingFailuresDoNotRepeatTheRejectedValue() {
        RegistrationForm form = new RegistrationForm();
        form.setPassword(SECRET);
        form.setEmail(SECRET);
        BindException failure = new BindException(form, "registrationForm");
        failure.rejectValue("password", "Size", "Password must be between 8 and 72 characters");
        failure.rejectValue("email", "Email", "Enter a valid email address");

        // Spring's own message is exactly what must not be logged.
        assertThat(failure.getMessage()).contains(SECRET);
        assertThat(LogValues.exception(failure))
                .isEqualTo("BindException: 2 validation error(s) on [email, password]")
                .doesNotContain(SECRET);
        assertThat(LogValues.fieldErrors(failure)).isEqualTo("2 validation error(s) on [email, password]");
    }

    @Test
    @DisplayName("a form-level error with no field is still counted")
    void formLevelErrorsAreCounted() {
        BindException failure = new BindException(new RegistrationForm(), "registrationForm");
        failure.reject("form.invalid", "The form could not be accepted");

        assertThat(LogValues.fieldErrors(failure)).isEqualTo("1 validation error(s)");
    }

    @Test
    @DisplayName("a conversion failure names the parameter and the type wanted; the value sent is not repeated")
    void conversionFailuresDoNotRepeatTheValue() throws Exception {
        MethodParameter id = new MethodParameter(
                CatalogController.class.getMethod("detail", Long.class, Model.class), 0);
        MethodArgumentTypeMismatchException failure = new MethodArgumentTypeMismatchException(
                SECRET, Long.class, "id", id, new NumberFormatException("For input string: \"" + SECRET + "\""));

        assertThat(failure.getMessage()).contains(SECRET);
        assertThat(LogValues.exception(failure))
                .isEqualTo("MethodArgumentTypeMismatchException: parameter 'id' could not be converted to Long")
                .doesNotContain(SECRET);

        assertThat(LogValues.exception(new TypeMismatchException(SECRET, LocalDate.class)))
                .isEqualTo("TypeMismatchException: a value could not be converted to LocalDate")
                .doesNotContain(SECRET);
    }
}
