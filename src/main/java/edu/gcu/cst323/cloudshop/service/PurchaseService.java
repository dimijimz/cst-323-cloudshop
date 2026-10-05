package edu.gcu.cst323.cloudshop.service;

import edu.gcu.cst323.cloudshop.exception.InsufficientStockException;
import edu.gcu.cst323.cloudshop.exception.ResourceNotFoundException;
import edu.gcu.cst323.cloudshop.model.Product;
import edu.gcu.cst323.cloudshop.model.Purchase;
import edu.gcu.cst323.cloudshop.model.User;
import edu.gcu.cst323.cloudshop.repository.ProductRepository;
import edu.gcu.cst323.cloudshop.repository.PurchaseRepository;
import edu.gcu.cst323.cloudshop.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Business layer for buying: the purchase transaction itself, a buyer's own
 * history, and the owner's sales report.
 */
@Service
@Transactional(readOnly = true)
public class PurchaseService {

    private static final Logger log = LoggerFactory.getLogger(PurchaseService.class);

    private final PurchaseRepository purchaseRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;

    /**
     * Creates the service.
     *
     * @param purchaseRepository data access for purchase records
     * @param productRepository  data access for products, including the stock decrement
     * @param userRepository     data access for accounts, to resolve the buyer
     */
    public PurchaseService(PurchaseRepository purchaseRepository,
                           ProductRepository productRepository,
                           UserRepository userRepository) {
        this.purchaseRepository = purchaseRepository;
        this.productRepository = productRepository;
        this.userRepository = userRepository;
    }

    /**
     * Buys a quantity of a product for a buyer, in one transaction.
     *
     * <p>The stock is decremented first, with a conditional update that only
     * succeeds if enough units are left. That statement is the whole concurrency
     * control: of two buyers racing for the last unit, exactly one update matches a
     * row. Only after it succeeds is the purchase row inserted, carrying the unit
     * price at this moment. If anything after the decrement fails, the transaction
     * rolls back and the stock is restored.
     *
     * @param username  the buyer's sign-in name
     * @param productId the product to buy
     * @param quantity  how many units, at least one
     * @return the recorded purchase, with its product and buyer loaded
     * @throws IllegalArgumentException   if the quantity is less than one
     * @throws ResourceNotFoundException  if the product does not exist or is inactive,
     *                                    or the buyer's account cannot be found
     * @throws InsufficientStockException if fewer than {@code quantity} units are left
     */
    @Transactional
    public Purchase purchase(String username, Long productId, int quantity) {
        if (quantity < 1) {
            throw new IllegalArgumentException("Quantity must be at least 1");
        }

        int rowsUpdated = productRepository.decrementStock(productId, quantity);

        // Loaded after the update, so this is the row as the update left it.
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product", productId));

        if (rowsUpdated == 0) {
            if (!product.isActive()) {
                throw new ResourceNotFoundException("Product", productId);
            }
            throw new InsufficientStockException(product.getId(), product.getName(), quantity, product.getStock());
        }

        User buyer = userRepository.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new ResourceNotFoundException("No account named " + username));

        Purchase saved = purchaseRepository.save(new Purchase(buyer, product, quantity, product.getPrice()));
        log.info("CREATE purchase: id={} buyer={} product={} quantity={} unitPrice={} total={} stockLeft={}",
                saved.getId(), buyer.getUsername(), product.getName(), quantity,
                saved.getUnitPrice(), saved.getTotalPrice(), product.getStock());
        return saved;
    }

    /**
     * Lists one buyer's own purchases for the "My purchases" page.
     *
     * @param username the buyer's sign-in name
     * @return that buyer's purchases, newest first
     */
    public List<Purchase> findForBuyer(String username) {
        return purchaseRepository.findByBuyerUsername(username);
    }

    /**
     * Lists purchases for the owner's sales report. Every filter is optional.
     *
     * <p>Both dates are inclusive: a report from the 3rd to the 3rd covers the
     * whole of the 3rd. Days are taken in the server's time zone, the same zone
     * the purchase times are recorded in.
     *
     * @param productId only purchases of this product, or null for all products
     * @param from      the first day to include, or null for no start date
     * @param to        the last day to include, or null for no end date
     * @return the matching purchases, newest first
     */
    public List<Purchase> findSales(Long productId, LocalDate from, LocalDate to) {
        LocalDateTime start = from == null ? null : from.atStartOfDay();
        // Exclusive upper bound at the following midnight, so the end day is covered in full.
        LocalDateTime end = to == null ? null : to.plusDays(1).atStartOfDay();
        return purchaseRepository.findSales(productId, start, end);
    }

    /**
     * Adds up what a list of purchases brought in. The sales page calls this with
     * the same list it displays, so the total always agrees with the rows shown.
     *
     * @param purchases the purchases to total
     * @return the sum of their totals, or zero for an empty list
     */
    public BigDecimal totalRevenue(List<Purchase> purchases) {
        return purchases.stream()
                .map(Purchase::getTotalPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
