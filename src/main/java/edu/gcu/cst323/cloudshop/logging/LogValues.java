package edu.gcu.cst323.cloudshop.logging;

import jakarta.persistence.Persistence;
import jakarta.validation.ConstraintViolationException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.validation.Errors;
import org.springframework.validation.FieldError;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.lang.reflect.Array;
import java.time.Instant;
import java.time.temporal.Temporal;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns values into what the log is allowed to say about them.
 *
 * <p>The rule is an allow-list, not a block-list. A value is printed only when its
 * type cannot hold a secret or personal data in this application: numbers,
 * booleans, enums and dates. Anything else is reduced to its type, such as
 * {@code <String>} or {@code <RegistrationForm>}. Nothing here decides by
 * parameter name, so a password is safe whatever its parameter is called, and a
 * field added to a form later is safe the day it is added.
 *
 * <p>Results are never printed, only typed, with the size added for collections.
 */
public final class LogValues {

    private static final Pattern LINE_BREAKS = Pattern.compile("\\s*\\R\\s*");

    private LogValues() {
    }

    /**
     * Describes one method argument.
     *
     * @param declaredType the parameter's declared type, used to name values that
     *                     are not printed; may be null
     * @param value        the argument
     * @return the value itself for a number, boolean, enum or date; {@code null}
     *         for null; otherwise the type in angle brackets
     */
    public static String argument(Class<?> declaredType, Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Boolean) {
            return value.toString();
        }
        if (value instanceof Enum<?> constant) {
            return constant.name();
        }
        if (value instanceof Date date) {
            return Instant.ofEpochMilli(date.getTime()).toString();
        }
        if ((value instanceof Number || value instanceof Temporal) && isJdkType(value)) {
            return value.toString();
        }
        return type(declaredType, value);
    }

    /**
     * Describes what a method returned, by type only.
     *
     * @param declaredType the method's declared return type; may be null
     * @param value        the returned value
     * @return {@code void} for a method that returns nothing, {@code null} for
     *         null, otherwise the type in angle brackets, with the size for a
     *         collection, map or array
     */
    public static String result(Class<?> declaredType, Object value) {
        if (declaredType == void.class || declaredType == Void.class) {
            return "void";
        }
        if (value == null) {
            return "null";
        }
        return type(declaredType, value);
    }

    /**
     * Describes an exception as {@code Type: message}, on one line.
     *
     * <p>A binding or conversion failure is summarised instead of quoted, because
     * Spring's own message for those repeats the rejected value, and the rejected
     * value may be a password typed into the wrong box. Other messages are passed
     * through; the application's own exceptions are written to carry no names or
     * addresses.
     *
     * @param ex the exception
     * @return its simple class name and a message that is safe to log
     */
    public static String exception(Throwable ex) {
        return simpleName(ex.getClass()) + ": " + message(ex);
    }

    /**
     * Summarises why a form or request was rejected, naming the fields at fault
     * but never what was typed into them.
     *
     * @param errors the binding or validation result
     * @return for example {@code 2 validation error(s) on [email, password]}
     */
    public static String fieldErrors(Errors errors) {
        List<String> fields = errors.getFieldErrors().stream()
                .map(FieldError::getField)
                .distinct()
                .sorted()
                .toList();
        String summary = errors.getErrorCount() + " validation error(s)";
        return fields.isEmpty() ? summary : summary + " on " + fields;
    }

    private static String message(Throwable ex) {
        if (ex instanceof Errors errors) {
            return fieldErrors(errors);
        }
        if (ex instanceof TypeMismatchException mismatch) {
            String subject = mismatch instanceof MethodArgumentTypeMismatchException argument
                    ? "parameter '" + argument.getName() + "'"
                    : "a value";
            String target = mismatch.getRequiredType() == null
                    ? "the required type"
                    : simpleName(mismatch.getRequiredType());
            return subject + " could not be converted to " + target;
        }
        if (ex instanceof ConstraintViolationException violations && violations.getConstraintViolations() != null) {
            List<String> paths = violations.getConstraintViolations().stream()
                    .map(violation -> violation.getPropertyPath().toString())
                    .distinct()
                    .sorted()
                    .toList();
            return violations.getConstraintViolations().size() + " constraint violation(s) on " + paths;
        }
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return "(no message)";
        }
        // Database and driver messages span lines; an entry in this log is one line.
        return LINE_BREAKS.matcher(message).replaceAll(" ").strip();
    }

    private static String type(Class<?> declaredType, Object value) {
        // A value declared as some other type is named by that type even when it
        // happens to be a map underneath: a Model is a Model, not a Map of size 0.
        if (!isContainerOrUnknown(declaredType)) {
            return "<" + simpleName(declaredType) + ">";
        }
        try {
            if (value instanceof Optional<?> optional) {
                return optional.isPresent()
                        ? "<Optional of " + simpleName(userClass(optional.get().getClass())) + ">"
                        : "<Optional empty>";
            }
            if (value instanceof Collection<?> collection) {
                String kind = value instanceof List ? "List" : value instanceof Set ? "Set" : "Collection";
                return isLoaded(value) ? "<" + kind + " size=" + collection.size() + ">" : "<" + kind + ">";
            }
            if (value instanceof Map<?, ?> map) {
                return isLoaded(value) ? "<Map size=" + map.size() + ">" : "<Map>";
            }
            if (value.getClass().isArray()) {
                return "<" + simpleName(value.getClass().getComponentType()) + "[] length=" + Array.getLength(value) + ">";
            }
        } catch (RuntimeException ex) {
            // Describing a value must never be what breaks the call being logged.
        }
        // Reached with a declared type that says nothing, as the erased type
        // variables of a Spring Data method do (save takes an "Object"), so the
        // class of the value itself is the only name there is.
        return "<" + simpleName(userClass(value.getClass())) + ">";
    }

    /**
     * True when the declared type either is one of the JDK's container types, whose
     * size is worth reporting, or tells the reader nothing.
     */
    private static boolean isContainerOrUnknown(Class<?> declaredType) {
        return declaredType == null
                || declaredType == Object.class
                || declaredType == Optional.class
                || declaredType.isArray()
                || Iterable.class.isAssignableFrom(declaredType)
                || Map.class.isAssignableFrom(declaredType);
    }

    /**
     * An unloaded lazy collection would run a query to answer size(), so asking
     * would let the log change what the application does. Its size is left out.
     */
    private static boolean isLoaded(Object value) {
        return Persistence.getPersistenceUtil().isLoaded(value);
    }

    private static boolean isJdkType(Object value) {
        return value.getClass().getName().startsWith("java.");
    }

    /** Steps past generated subclasses (CGLIB, Hibernate) to the class a developer wrote. */
    private static Class<?> userClass(Class<?> type) {
        Class<?> current = type;
        while (isGenerated(current) && current.getSuperclass() != null && current.getSuperclass() != Object.class) {
            current = current.getSuperclass();
        }
        return current;
    }

    private static boolean isGenerated(Class<?> type) {
        return type.getName().contains("$$") || type.getName().contains("$HibernateProxy$");
    }

    private static String simpleName(Class<?> type) {
        String name = type.getSimpleName();
        if (!name.isEmpty()) {
            return name;
        }
        // Anonymous classes have no simple name.
        return type.getName().substring(type.getName().lastIndexOf('.') + 1);
    }
}
