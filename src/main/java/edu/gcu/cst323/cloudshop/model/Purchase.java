package edu.gcu.cst323.cloudshop.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * One completed sale: a buyer bought some quantity of a product.
 *
 * <p>Maps the {@code purchases} table. A purchase is a record of something that
 * happened, so it has no setters and every column is marked non-updatable. In
 * particular it keeps its own copy of the unit price, which is why editing a
 * product's price later leaves past purchases exactly as they were.
 */
@Entity
@Table(name = "purchases")
public class Purchase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "buyer_id", nullable = false, updatable = false)
    private User buyer;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, updatable = false)
    private Product product;

    @Column(name = "quantity", nullable = false, updatable = false)
    private int quantity;

    @Column(name = "unit_price", nullable = false, updatable = false, precision = 10, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "total_price", nullable = false, updatable = false, precision = 16, scale = 2)
    private BigDecimal totalPrice;

    @Column(name = "purchased_at", nullable = false, updatable = false)
    private LocalDateTime purchasedAt;

    /** Required by JPA; application code uses the other constructor. */
    protected Purchase() {
    }

    /**
     * Records a sale happening now. The total is calculated here, once, from the
     * unit price and quantity.
     *
     * @param buyer     the account making the purchase
     * @param product   the product being bought
     * @param quantity  how many units, at least one
     * @param unitPrice the price per unit at this moment
     */
    public Purchase(User buyer, Product product, int quantity, BigDecimal unitPrice) {
        this.buyer = buyer;
        this.product = product;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.totalPrice = unitPrice.multiply(BigDecimal.valueOf(quantity));
        // The column keeps microseconds; trimming here keeps memory and database equal.
        this.purchasedAt = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * Returns the database identifier.
     *
     * @return the generated id, or null before the purchase is saved
     */
    public Long getId() {
        return id;
    }

    /**
     * Returns who made the purchase.
     *
     * @return the buyer's account
     */
    public User getBuyer() {
        return buyer;
    }

    /**
     * Returns what was bought.
     *
     * @return the product, as it is now rather than as it was at the time of sale
     */
    public Product getProduct() {
        return product;
    }

    /**
     * Returns how many units were bought.
     *
     * @return the quantity, at least one
     */
    public int getQuantity() {
        return quantity;
    }

    /**
     * Returns the price per unit that was actually paid.
     *
     * @return the unit price at the time of sale
     */
    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    /**
     * Returns what the purchase cost in all.
     *
     * @return the unit price multiplied by the quantity
     */
    public BigDecimal getTotalPrice() {
        return totalPrice;
    }

    /**
     * Returns when the purchase was made.
     *
     * @return the purchase time, in the server's time zone
     */
    public LocalDateTime getPurchasedAt() {
        return purchasedAt;
    }

    /**
     * Describes the purchase for log output without touching the lazy associations.
     *
     * @return a short description naming the id, quantity, prices and time
     */
    @Override
    public String toString() {
        return "Purchase{id=" + id + ", quantity=" + quantity + ", unitPrice=" + unitPrice
                + ", totalPrice=" + totalPrice + ", purchasedAt=" + purchasedAt + "}";
    }
}
