package edu.gcu.cst323.cloudshop.form;

import edu.gcu.cst323.cloudshop.model.Product;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Backs the owner's create and edit product form.
 *
 * <p>A separate form object, rather than binding the {@link Product} entity
 * directly, so a request can only ever set these four fields. The id comes from
 * the URL and the active flag has its own deactivate and reactivate actions.
 */
public class ProductForm {

    @NotBlank(message = "Name is required")
    @Size(max = 120, message = "Name must be 120 characters or fewer")
    private String name;

    @NotBlank(message = "Description is required")
    @Size(max = 1000, message = "Description must be 1000 characters or fewer")
    private String description;

    // The limits mirror the columns: price is DECIMAL(10,2), and with stock capped at a
    // million units the largest possible purchase total still fits total_price DECIMAL(16,2).
    @NotNull(message = "Price is required")
    @DecimalMin(value = "0.00", message = "Price cannot be negative")
    @Digits(integer = 8, fraction = 2, message = "Price must be under 100,000,000 with at most 2 decimal places")
    private BigDecimal price;

    @NotNull(message = "Stock is required")
    @Min(value = 0, message = "Stock cannot be negative")
    @Max(value = 1_000_000, message = "Stock cannot exceed 1,000,000")
    private Integer stock;

    /** Creates an empty form for the blank "Add product" page. */
    public ProductForm() {
    }

    /**
     * Builds a form pre-filled from an existing product, for the edit page.
     *
     * @param product the product being edited
     * @return a form carrying that product's current values
     */
    public static ProductForm from(Product product) {
        ProductForm form = new ProductForm();
        form.setName(product.getName());
        form.setDescription(product.getDescription());
        form.setPrice(product.getPrice());
        form.setStock(product.getStock());
        return form;
    }

    /**
     * Returns the product name.
     *
     * @return the name as typed
     */
    public String getName() {
        return name;
    }

    /**
     * Sets the product name.
     *
     * @param name the name as typed
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * Returns the product description.
     *
     * @return the description as typed
     */
    public String getDescription() {
        return description;
    }

    /**
     * Sets the product description.
     *
     * @param description the description as typed
     */
    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * Returns the unit price.
     *
     * @return the price, or null if the field was left empty
     */
    public BigDecimal getPrice() {
        return price;
    }

    /**
     * Sets the unit price.
     *
     * @param price the price entered
     */
    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    /**
     * Returns the number of units on hand.
     *
     * @return the stock level, or null if the field was left empty
     */
    public Integer getStock() {
        return stock;
    }

    /**
     * Sets the number of units on hand.
     *
     * @param stock the stock level entered
     */
    public void setStock(Integer stock) {
        this.stock = stock;
    }
}
