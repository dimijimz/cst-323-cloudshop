package edu.gcu.cst323.cloudshop.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * Something the shop sells.
 *
 * <p>Maps the {@code products} table. A product is never deleted, because past
 * purchases reference it; the owner deactivates it instead, which hides it from
 * the public catalog while leaving the sales history intact.
 *
 * <p>The {@code stock} field is what the owner sets from the inventory form. A
 * purchase does not go through this setter: it decrements the column directly in
 * the database, with a condition, so that two buyers cannot both take the last unit.
 */
@Entity
@Table(name = "products")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "description", nullable = false, length = 1000)
    private String description;

    @Column(name = "price", nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(name = "stock", nullable = false)
    private int stock;

    @Column(name = "active", nullable = false)
    private boolean active;

    /** Required by JPA; application code uses the other constructor. */
    protected Product() {
    }

    /**
     * Creates a new, not yet saved product that is active in the catalog.
     *
     * @param name        the display name
     * @param description the text shown on the catalog and detail pages
     * @param price       the current unit price, zero or more
     * @param stock       the number of units on hand, zero or more
     */
    public Product(String name, String description, BigDecimal price, int stock) {
        this.name = name;
        this.description = description;
        this.price = price;
        this.stock = stock;
        this.active = true;
    }

    /**
     * Returns the database identifier.
     *
     * @return the generated id, or null before the product is saved
     */
    public Long getId() {
        return id;
    }

    /**
     * Returns the display name.
     *
     * @return the product name
     */
    public String getName() {
        return name;
    }

    /**
     * Changes the display name.
     *
     * @param name the new product name
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * Returns the text shown on the catalog and detail pages.
     *
     * @return the description
     */
    public String getDescription() {
        return description;
    }

    /**
     * Changes the description.
     *
     * @param description the new description
     */
    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * Returns the current unit price.
     *
     * @return the price a buyer would pay now
     */
    public BigDecimal getPrice() {
        return price;
    }

    /**
     * Changes the unit price. Purchases already made keep the price they were made at.
     *
     * @param price the new unit price, zero or more
     */
    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    /**
     * Returns the number of units on hand.
     *
     * @return the stock level
     */
    public int getStock() {
        return stock;
    }

    /**
     * Sets the number of units on hand, as entered by the owner.
     *
     * @param stock the new stock level, zero or more
     */
    public void setStock(int stock) {
        this.stock = stock;
    }

    /**
     * Reports whether the product is offered in the public catalog.
     *
     * @return true if buyers can see and purchase it
     */
    public boolean isActive() {
        return active;
    }

    /**
     * Shows or hides the product in the public catalog.
     *
     * @param active true to offer it to buyers, false to deactivate it
     */
    public void setActive(boolean active) {
        this.active = active;
    }

    /**
     * Reports whether at least one unit can be bought. Used by the templates to
     * mark out-of-stock products and withhold the purchase form.
     *
     * @return true if the stock level is above zero
     */
    public boolean isInStock() {
        return stock > 0;
    }

    /**
     * Describes the product for log output.
     *
     * @return a short description naming the id, name, price and stock
     */
    @Override
    public String toString() {
        return "Product{id=" + id + ", name=" + name + ", price=" + price + ", stock=" + stock
                + ", active=" + active + "}";
    }
}
