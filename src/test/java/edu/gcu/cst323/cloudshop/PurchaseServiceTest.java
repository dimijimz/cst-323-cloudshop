package edu.gcu.cst323.cloudshop;

import edu.gcu.cst323.cloudshop.exception.InsufficientStockException;
import edu.gcu.cst323.cloudshop.exception.ResourceNotFoundException;
import edu.gcu.cst323.cloudshop.form.ProductForm;
import edu.gcu.cst323.cloudshop.form.RegistrationForm;
import edu.gcu.cst323.cloudshop.model.Product;
import edu.gcu.cst323.cloudshop.model.Purchase;
import edu.gcu.cst323.cloudshop.repository.ProductRepository;
import edu.gcu.cst323.cloudshop.repository.PurchaseRepository;
import edu.gcu.cst323.cloudshop.repository.UserRepository;
import edu.gcu.cst323.cloudshop.service.ProductService;
import edu.gcu.cst323.cloudshop.service.PurchaseService;
import edu.gcu.cst323.cloudshop.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the purchase transaction against a real database, built by Flyway
 * from the production migrations.
 *
 * <p>Deliberately not {@code @Transactional}. A test-managed transaction would
 * wrap the service's own, hiding whether it commits and rolls back correctly,
 * and the concurrency tests need each buyer's purchase to run in a separate
 * transaction on a separate connection, as it would in production.
 */
@SpringBootTest
@ActiveProfiles({"h2", "test"})
class PurchaseServiceTest {

    @Autowired
    private PurchaseService purchaseService;

    @Autowired
    private ProductService productService;

    @Autowired
    private UserService userService;

    @Autowired
    private PurchaseRepository purchaseRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        purchaseRepository.deleteAll();
        productRepository.deleteAll();
        userRepository.deleteAll();
        userService.registerBuyer(buyer("alice"));
        userService.registerBuyer(buyer("bob"));
    }

    // --- normal purchase ----------------------------------------------------

    @Test
    @DisplayName("a purchase takes the units out of stock and records the price paid, total and time")
    void purchaseDecrementsStockAndRecordsThePricePaid() {
        Product keyboard = product("Keyboard", "79.99", 10);
        LocalDateTime before = LocalDateTime.now().minusSeconds(1);

        Purchase purchase = purchaseService.purchase("alice", keyboard.getId(), 3);

        assertThat(purchase.getId()).isNotNull();
        assertThat(purchase.getQuantity()).isEqualTo(3);
        assertThat(purchase.getUnitPrice()).isEqualByComparingTo("79.99");
        assertThat(purchase.getTotalPrice()).isEqualByComparingTo("239.97");
        assertThat(purchase.getPurchasedAt()).isBetween(before, LocalDateTime.now().plusSeconds(1));
        assertThat(stockOf(keyboard)).isEqualTo(7);

        // And it is really in the database, attributed to the right buyer.
        List<Purchase> history = purchaseService.findForBuyer("alice");
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getId()).isEqualTo(purchase.getId());
        assertThat(history.get(0).getProduct().getName()).isEqualTo("Keyboard");
        assertThat(history.get(0).getTotalPrice()).isEqualByComparingTo("239.97");
    }

    @Test
    @DisplayName("buying exactly what is left succeeds and leaves the product out of stock")
    void buyingAllRemainingStockLeavesZero() {
        Product monitor = product("Monitor", "329.00", 4);

        purchaseService.purchase("alice", monitor.getId(), 4);

        assertThat(stockOf(monitor)).isZero();
        assertThat(productRepository.findById(monitor.getId()).orElseThrow().isInStock()).isFalse();
    }

    @Test
    @DisplayName("each buyer's history holds only that buyer's purchases")
    void buyersOnlySeeTheirOwnHistory() {
        Product keyboard = product("Keyboard", "79.99", 10);
        purchaseService.purchase("alice", keyboard.getId(), 1);
        purchaseService.purchase("bob", keyboard.getId(), 2);
        purchaseService.purchase("bob", keyboard.getId(), 3);

        assertThat(purchaseService.findForBuyer("alice")).extracting(Purchase::getQuantity).containsExactly(1);
        assertThat(purchaseService.findForBuyer("bob")).extracting(Purchase::getQuantity).containsExactlyInAnyOrder(2, 3);
    }

    // --- insufficient stock -------------------------------------------------

    @Test
    @DisplayName("asking for more than is in stock is rejected, and nothing is sold or recorded")
    void insufficientStockIsRejectedAndChangesNothing() {
        Product webcam = product("Webcam", "49.95", 2);

        assertThatThrownBy(() -> purchaseService.purchase("alice", webcam.getId(), 3))
                .isInstanceOfSatisfying(InsufficientStockException.class, ex -> {
                    assertThat(ex.getProductId()).isEqualTo(webcam.getId());
                    assertThat(ex.getProductName()).isEqualTo("Webcam");
                    assertThat(ex.getRequested()).isEqualTo(3);
                    assertThat(ex.getAvailable()).isEqualTo(2);
                })
                .hasMessageContaining("Only 2 of Webcam left")
                .hasMessageContaining("Nothing was purchased");

        assertThat(stockOf(webcam)).isEqualTo(2);
        assertThat(purchaseRepository.count()).isZero();
    }

    @Test
    @DisplayName("an out-of-stock product cannot be purchased at all")
    void outOfStockProductCannotBePurchased() {
        Product stand = product("Laptop Stand", "34.00", 0);

        assertThatThrownBy(() -> purchaseService.purchase("alice", stand.getId(), 1))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("Laptop Stand is out of stock");

        assertThat(stockOf(stand)).isZero();
        assertThat(purchaseRepository.count()).isZero();
    }

    @Test
    @DisplayName("a failure after the stock decrement rolls the decrement back")
    void failureAfterDecrementRestoresStock() {
        Product keyboard = product("Keyboard", "79.99", 10);

        // The stock update succeeds, then the buyer lookup fails inside the same transaction.
        assertThatThrownBy(() -> purchaseService.purchase("nobody", keyboard.getId(), 4))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThat(stockOf(keyboard)).isEqualTo(10);
        assertThat(purchaseRepository.count()).isZero();
    }

    // --- price history ------------------------------------------------------

    @Test
    @DisplayName("a later price change does not alter what past purchases cost")
    void laterPriceChangeDoesNotAlterPastPurchases() {
        Product headphones = product("Headphones", "50.00", 10);
        Purchase atOldPrice = purchaseService.purchase("alice", headphones.getId(), 2);

        // The owner reprices the product after the sale.
        ProductForm repriced = ProductForm.from(productRepository.findById(headphones.getId()).orElseThrow());
        repriced.setPrice(new BigDecimal("75.00"));
        productService.update(headphones.getId(), repriced);

        // The earlier purchase, read back from the database, still shows what was paid.
        Purchase reloaded = purchaseRepository.findById(atOldPrice.getId()).orElseThrow();
        assertThat(reloaded.getUnitPrice()).isEqualByComparingTo("50.00");
        assertThat(reloaded.getTotalPrice()).isEqualByComparingTo("100.00");

        // A purchase made now pays the new price.
        Purchase atNewPrice = purchaseService.purchase("alice", headphones.getId(), 1);
        assertThat(atNewPrice.getUnitPrice()).isEqualByComparingTo("75.00");
        assertThat(atNewPrice.getTotalPrice()).isEqualByComparingTo("75.00");

        // Both prices coexist in the history, and revenue is what was actually charged.
        List<Purchase> sales = purchaseService.findSales(headphones.getId(), null, null);
        assertThat(sales).extracting(p -> p.getUnitPrice().toPlainString()).containsExactlyInAnyOrder("50.00", "75.00");
        assertThat(purchaseService.totalRevenue(sales)).isEqualByComparingTo("175.00");
    }

    // --- things that are not there ------------------------------------------

    @Test
    @DisplayName("a deactivated product cannot be purchased, even by someone who still has its id")
    void inactiveProductCannotBePurchased() {
        Product retired = product("Retired Gadget", "10.00", 5);
        productService.deactivate(retired.getId());

        assertThatThrownBy(() -> purchaseService.purchase("alice", retired.getId(), 1))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThat(stockOf(retired)).isEqualTo(5);
        assertThat(purchaseRepository.count()).isZero();
    }

    @Test
    @DisplayName("an unknown product id is reported as not found")
    void unknownProductIsNotFound() {
        assertThatThrownBy(() -> purchaseService.purchase("alice", 999_999L, 1))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("999999");
    }

    @Test
    @DisplayName("a quantity below one is rejected before the database is touched")
    void quantityBelowOneIsRejected() {
        Product keyboard = product("Keyboard", "79.99", 10);

        assertThatThrownBy(() -> purchaseService.purchase("alice", keyboard.getId(), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> purchaseService.purchase("alice", keyboard.getId(), -5))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(stockOf(keyboard)).isEqualTo(10);
    }

    // --- concurrency --------------------------------------------------------

    @Test
    @DisplayName("two buyers racing for the last unit cannot both buy it")
    void twoBuyersCannotBothBuyTheLastUnit() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // Repeated, because a race that is lost one time in ten would pass a single run.
            for (int round = 1; round <= 25; round++) {
                Product lastOne = product("Last Unit " + round, "49.95", 1);
                List<Boolean> outcomes = race(pool, List.of("alice", "bob"), lastOne.getId());

                assertThat(outcomes).as("round %d: exactly one of the two buyers gets the unit", round)
                        .containsExactlyInAnyOrder(true, false);
                assertThat(stockOf(lastOne)).as("round %d: stock", round).isZero();
                assertThat(purchaseService.findSales(lastOne.getId(), null, null))
                        .as("round %d: purchases recorded", round).hasSize(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("under many simultaneous buyers, exactly the units in stock are sold and stock never goes negative")
    void stockIsNeverOversoldUnderConcurrentPurchases() throws Exception {
        int stock = 4;
        int buyers = 10;
        Product scarce = product("Scarce Item", "20.00", stock);

        List<String> usernames = new ArrayList<>();
        for (int i = 0; i < buyers; i++) {
            usernames.add(i % 2 == 0 ? "alice" : "bob");
        }

        ExecutorService pool = Executors.newFixedThreadPool(buyers);
        try {
            List<Boolean> outcomes = race(pool, usernames, scarce.getId());

            assertThat(outcomes).filteredOn(Boolean::booleanValue).hasSize(stock);
            assertThat(stockOf(scarce)).isZero();

            List<Purchase> sales = purchaseService.findSales(scarce.getId(), null, null);
            assertThat(sales).hasSize(stock);
            assertThat(purchaseService.totalRevenue(sales)).isEqualByComparingTo("80.00");
        } finally {
            pool.shutdownNow();
        }
    }

    // --- sales report -------------------------------------------------------

    @Test
    @DisplayName("the sales report filters by product and by an inclusive date range, and totals revenue")
    void salesReportFiltersAndTotals() {
        Product keyboard = product("Keyboard", "79.99", 10);
        Product mouse = product("Mouse", "24.99", 10);
        purchaseService.purchase("alice", keyboard.getId(), 2);   // 159.98
        purchaseService.purchase("bob", mouse.getId(), 1);        //  24.99
        purchaseService.purchase("bob", keyboard.getId(), 1);     //  79.99

        LocalDate today = LocalDate.now();

        List<Purchase> all = purchaseService.findSales(null, null, null);
        assertThat(all).hasSize(3);
        assertThat(purchaseService.totalRevenue(all)).isEqualByComparingTo("264.96");
        // The buyer and product are loaded with each row, not left as lazy proxies.
        assertThat(all).extracting(p -> p.getBuyer().getUsername()).containsOnly("alice", "bob");

        List<Purchase> keyboards = purchaseService.findSales(keyboard.getId(), null, null);
        assertThat(keyboards).hasSize(2);
        assertThat(purchaseService.totalRevenue(keyboards)).isEqualByComparingTo("239.97");

        // A range of a single day includes that whole day.
        assertThat(purchaseService.findSales(null, today, today)).hasSize(3);
        assertThat(purchaseService.findSales(mouse.getId(), today, today)).hasSize(1);
        assertThat(purchaseService.findSales(null, today.minusDays(7), null)).hasSize(3);
        assertThat(purchaseService.findSales(null, null, today.plusDays(7))).hasSize(3);

        // Ranges that end before today or start after it match nothing.
        assertThat(purchaseService.findSales(null, null, today.minusDays(1))).isEmpty();
        assertThat(purchaseService.findSales(null, today.plusDays(1), null)).isEmpty();

        assertThat(purchaseService.totalRevenue(List.of())).isEqualByComparingTo("0");
    }

    // --- helpers ------------------------------------------------------------

    /**
     * Starts one purchase of a single unit per username, all released at the same
     * instant, and reports for each whether it succeeded. Any failure other than
     * running out of stock fails the test.
     */
    private List<Boolean> race(ExecutorService pool, List<String> usernames, Long productId) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> attempts = new ArrayList<>();
        for (String username : usernames) {
            Callable<Boolean> attempt = () -> {
                start.await();
                try {
                    purchaseService.purchase(username, productId, 1);
                    return true;
                } catch (InsufficientStockException soldOut) {
                    return false;
                }
            };
            attempts.add(pool.submit(attempt));
        }
        start.countDown();

        List<Boolean> outcomes = new ArrayList<>();
        for (Future<Boolean> attempt : attempts) {
            outcomes.add(attempt.get(30, TimeUnit.SECONDS));
        }
        return outcomes;
    }

    private Product product(String name, String price, int stock) {
        return productRepository.save(new Product(name, "Test product", new BigDecimal(price), stock));
    }

    private int stockOf(Product product) {
        return productRepository.findById(product.getId()).orElseThrow().getStock();
    }

    private static RegistrationForm buyer(String username) {
        RegistrationForm form = new RegistrationForm();
        form.setUsername(username);
        form.setEmail(username + "@example.com");
        form.setPassword("test-password-" + username);
        form.setConfirmPassword("test-password-" + username);
        return form;
    }
}
