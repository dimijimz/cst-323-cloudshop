package edu.gcu.cst323.cloudshop.repository;

import edu.gcu.cst323.cloudshop.model.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Data access for {@link Product} rows, including the stock decrement a purchase relies on. */
@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {

    /**
     * Lists the public catalog: active products only, by name.
     *
     * @return every active product in alphabetical order
     */
    List<Product> findByActiveTrueOrderByNameAsc();

    /**
     * Lists the owner's inventory: every product, active or not, by name.
     *
     * @return every product in alphabetical order
     */
    List<Product> findAllByOrderByNameAsc();

    /**
     * Finds a product only if it is currently offered in the catalog.
     *
     * @param id the product id
     * @return the product, or empty if it does not exist or has been deactivated
     */
    Optional<Product> findByIdAndActiveTrue(Long id);

    /**
     * Takes units out of stock, but only if that many are available.
     *
     * <p>This single conditional UPDATE is what stops two buyers from both buying
     * the last unit. The check and the decrement happen in one statement, under the
     * row lock the database takes for the update, so there is no gap between reading
     * the stock and writing it for a second buyer to slip through. Whichever request
     * runs second finds the condition false and changes nothing.
     *
     * <p>The persistence context is cleared afterwards because a bulk update bypasses
     * it; any Product loaded earlier in the transaction would still show the old stock.
     *
     * @param id       the product to sell from
     * @param quantity how many units to take
     * @return 1 if the stock was decremented, or 0 if the product is missing,
     *         inactive, or has fewer than {@code quantity} units left
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Product p
               set p.stock = p.stock - :quantity
             where p.id = :id
               and p.active = true
               and p.stock >= :quantity
            """)
    int decrementStock(@Param("id") Long id, @Param("quantity") int quantity);
}
