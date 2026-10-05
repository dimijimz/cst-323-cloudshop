package edu.gcu.cst323.cloudshop.repository;

import edu.gcu.cst323.cloudshop.model.Purchase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Data access for {@link Purchase} records.
 *
 * <p>Both queries fetch the associations the pages display in the same round trip.
 * Open-session-in-view is switched off, so a lazy association left unfetched here
 * could not be loaded later from a template.
 */
@Repository
public interface PurchaseRepository extends JpaRepository<Purchase, Long> {

    /**
     * Lists one buyer's purchases, newest first, for the "My purchases" page.
     *
     * @param username the buyer's exact sign-in name
     * @return that buyer's purchases with their products loaded
     */
    @Query("""
            select p
              from Purchase p
              join fetch p.product
             where p.buyer.username = :username
             order by p.purchasedAt desc, p.id desc
            """)
    List<Purchase> findByBuyerUsername(@Param("username") String username);

    /**
     * Lists purchases for the owner's sales report, newest first. Every filter is
     * optional: a null argument leaves that filter off.
     *
     * @param productId only purchases of this product, or null for all products
     * @param from      only purchases at or after this moment, or null for no lower bound
     * @param to        only purchases before this moment, or null for no upper bound
     * @return the matching purchases with their buyers and products loaded
     */
    @Query("""
            select p
              from Purchase p
              join fetch p.buyer
              join fetch p.product
             where (:productId is null or p.product.id = :productId)
               and (:from is null or p.purchasedAt >= :from)
               and (:to is null or p.purchasedAt < :to)
             order by p.purchasedAt desc, p.id desc
            """)
    List<Purchase> findSales(@Param("productId") Long productId,
                             @Param("from") LocalDateTime from,
                             @Param("to") LocalDateTime to);
}
