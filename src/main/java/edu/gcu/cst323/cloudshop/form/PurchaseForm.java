package edu.gcu.cst323.cloudshop.form;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Backs the quantity form on a product's detail page.
 *
 * <p>Only the lower bound is validated here. Whether enough stock exists is not a
 * property of the form: it is decided inside the purchase transaction, at the
 * moment of sale, because the stock shown on the page may already be out of date.
 */
public class PurchaseForm {

    @NotNull(message = "Quantity is required")
    @Min(value = 1, message = "Quantity must be at least 1")
    private Integer quantity = 1;

    /** Creates a form asking for one unit, the default shown on the page. */
    public PurchaseForm() {
    }

    /**
     * Returns how many units the buyer wants.
     *
     * @return the quantity, or null if the field was left empty
     */
    public Integer getQuantity() {
        return quantity;
    }

    /**
     * Sets how many units the buyer wants.
     *
     * @param quantity the quantity entered
     */
    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }
}
