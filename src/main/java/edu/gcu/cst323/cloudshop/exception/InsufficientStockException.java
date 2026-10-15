package edu.gcu.cst323.cloudshop.exception;

/**
 * Raised when a buyer asks for more units than the shop has left.
 *
 * <p>Thrown from inside the purchase transaction after the conditional stock
 * update matched no row, so by the time it is raised nothing has been sold and
 * nothing has been recorded. {@link GlobalExceptionHandler} sends the buyer back
 * to the product page with the message.
 *
 * @author Dimitri Jimenez
 */
public class InsufficientStockException extends RuntimeException {

    /** The product that could not be bought; the buyer is sent back to its page. */
    private final Long productId;

    /** The product's display name, as it appears in the message. */
    private final String productName;

    /** How many units the buyer asked for. */
    private final int requested;

    /** How many units were left when the purchase was attempted. */
    private final int available;

    /**
     * Creates the exception, building a message that says what was left.
     *
     * @param productId   the product that could not be bought
     * @param productName its display name, for the message
     * @param requested   how many units the buyer asked for
     * @param available   how many units were left when the purchase was attempted
     */
    public InsufficientStockException(Long productId, String productName, int requested, int available) {
        super(describe(productName, requested, available));
        this.productId = productId;
        this.productName = productName;
        this.requested = requested;
        this.available = available;
    }

    /**
     * Builds the message the buyer sees, which says how much was left.
     *
     * @param productName the product's display name
     * @param requested   how many units were asked for
     * @param available   how many units were left
     * @return that the product is out of stock, or how many units were left and that
     *         the order could not be placed, followed by "Nothing was purchased."
     */
    private static String describe(String productName, int requested, int available) {
        String outcome = " Nothing was purchased.";
        if (available <= 0) {
            return productName + " is out of stock." + outcome;
        }
        return "Only " + available + " of " + productName + " left in stock, so an order for "
                + requested + " could not be placed." + outcome;
    }

    /**
     * Returns the product that could not be bought.
     *
     * @return the product id
     */
    public Long getProductId() {
        return productId;
    }

    /**
     * Returns the display name of the product.
     *
     * @return the product name
     */
    public String getProductName() {
        return productName;
    }

    /**
     * Returns how many units the buyer asked for.
     *
     * @return the requested quantity
     */
    public int getRequested() {
        return requested;
    }

    /**
     * Returns how many units were left when the purchase was attempted.
     *
     * @return the available stock, which is less than the requested quantity
     */
    public int getAvailable() {
        return available;
    }
}
