package edu.gcu.cst323.cloudshop.exception;

/**
 * Raised when a request refers to something that is not there, such as a product
 * id that does not exist or a product that has been deactivated.
 *
 * <p>{@link GlobalExceptionHandler} turns it into a 404 page.
 */
public class ResourceNotFoundException extends RuntimeException {

    /**
     * Creates the exception for a missing record identified by id.
     *
     * @param resource the kind of thing that was looked up, for example "Product"
     * @param id       the identifier that matched nothing
     */
    public ResourceNotFoundException(String resource, Object id) {
        super(resource + " " + id + " was not found");
    }

    /**
     * Creates the exception with a ready-made message.
     *
     * @param message what could not be found, in words fit to show the user
     */
    public ResourceNotFoundException(String message) {
        super(message);
    }
}
