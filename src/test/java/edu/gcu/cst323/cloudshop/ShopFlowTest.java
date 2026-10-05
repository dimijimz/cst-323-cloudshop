package edu.gcu.cst323.cloudshop;

import edu.gcu.cst323.cloudshop.form.RegistrationForm;
import edu.gcu.cst323.cloudshop.model.Product;
import edu.gcu.cst323.cloudshop.repository.ProductRepository;
import edu.gcu.cst323.cloudshop.repository.PurchaseRepository;
import edu.gcu.cst323.cloudshop.repository.UserRepository;
import edu.gcu.cst323.cloudshop.service.PurchaseService;
import edu.gcu.cst323.cloudshop.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Walks the shop's pages the way a browser would, which also renders every
 * Thymeleaf template for real: the catalog, a purchase and its confirmation,
 * the buyer's history, and the owner's inventory and sales pages.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"h2", "test"})
class ShopFlowTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserService userService;

    @Autowired
    private PurchaseService purchaseService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private PurchaseRepository purchaseRepository;

    private Product keyboard;
    private Product webcam;
    private Product stand;
    private Product retired;

    @BeforeEach
    void setUp() {
        purchaseRepository.deleteAll();
        productRepository.deleteAll();
        userRepository.deleteAll();

        RegistrationForm alice = new RegistrationForm();
        alice.setUsername("alice");
        alice.setEmail("alice@example.com");
        alice.setPassword("test-password-alice");
        alice.setConfirmPassword("test-password-alice");
        userService.registerBuyer(alice);

        keyboard = productRepository.save(new Product("Keyboard", "A test keyboard", new BigDecimal("79.99"), 10));
        webcam = productRepository.save(new Product("Webcam", "A test webcam", new BigDecimal("49.95"), 1));
        stand = productRepository.save(new Product("Laptop Stand", "A test stand", new BigDecimal("34.00"), 0));
        Product inactive = new Product("Retired Gadget", "No longer sold", new BigDecimal("10.00"), 5);
        inactive.setActive(false);
        retired = productRepository.save(inactive);
    }

    // --- public pages -------------------------------------------------------

    @Test
    @DisplayName("the home page renders and counts the catalog")
    void homePageRenders() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(view().name("index"))
                .andExpect(model().attribute("productCount", 3))
                .andExpect(content().string(containsString("CloudShop")));
    }

    @Test
    @DisplayName("the login page renders, with its banners")
    void loginPageRenders() throws Exception {
        mockMvc.perform(get("/login")).andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"username\"")))
                .andExpect(content().string(containsString("name=\"password\"")));
        mockMvc.perform(get("/login").param("error", ""))
                .andExpect(content().string(containsString("Invalid username or password")));
        mockMvc.perform(get("/login").param("registered", ""))
                .andExpect(content().string(containsString("Account created")));
        mockMvc.perform(get("/login").param("logout", ""))
                .andExpect(content().string(containsString("You have been signed out")));
    }

    @Test
    @DisplayName("the catalog lists active products with price and stock, marks out-of-stock ones and hides inactive ones")
    void catalogListsActiveProducts() throws Exception {
        mockMvc.perform(get("/products"))
                .andExpect(status().isOk())
                .andExpect(view().name("products/list"))
                .andExpect(model().attribute("products", hasSize(3)))
                .andExpect(content().string(containsString("Keyboard")))
                .andExpect(content().string(containsString("A test keyboard")))
                .andExpect(content().string(containsString("$79.99")))
                .andExpect(content().string(containsString("10 in stock")))
                .andExpect(content().string(containsString("Laptop Stand")))
                .andExpect(content().string(containsString("Out of stock")))
                .andExpect(content().string(not(containsString("Retired Gadget"))));
    }

    @Test
    @DisplayName("a product page shows the quantity form to a buyer only")
    void productDetailOffersPurchaseToBuyersOnly() throws Exception {
        String quantityField = "name=\"quantity\"";

        mockMvc.perform(get("/products/{id}", keyboard.getId()).with(alice()))
                .andExpect(status().isOk())
                .andExpect(view().name("products/detail"))
                .andExpect(content().string(containsString(quantityField)))
                .andExpect(content().string(containsString("/products/" + keyboard.getId() + "/purchase")));

        mockMvc.perform(get("/products/{id}", keyboard.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(quantityField))))
                .andExpect(content().string(containsString("create a buyer account")));

        mockMvc.perform(get("/products/{id}", keyboard.getId()).with(owner()))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(quantityField))))
                .andExpect(content().string(containsString("cannot make purchases")));
    }

    @Test
    @DisplayName("an out-of-stock product is marked and has no purchase form, even for a buyer")
    void outOfStockProductIsNotPurchasable() throws Exception {
        mockMvc.perform(get("/products/{id}", stand.getId()).with(alice()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Out of stock")))
                .andExpect(content().string(containsString("cannot be purchased right now")))
                .andExpect(content().string(not(containsString("name=\"quantity\""))));
    }

    @Test
    @DisplayName("an inactive or unknown product answers 404 on the public catalog")
    void inactiveAndUnknownProductsAreNotFound() throws Exception {
        mockMvc.perform(get("/products/{id}", retired.getId()))
                .andExpect(status().isNotFound())
                .andExpect(view().name("error"))
                .andExpect(content().string(not(containsString("Retired Gadget"))));
        mockMvc.perform(get("/products/{id}", 999_999))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("Product 999999 was not found")));
    }

    // --- buying -------------------------------------------------------------

    @Test
    @DisplayName("a buyer purchases, is shown a confirmation, and finds the purchase in their history")
    void buyerPurchasesAndSeesConfirmationAndHistory() throws Exception {
        mockMvc.perform(post("/products/{id}/purchase", keyboard.getId()).param("quantity", "2")
                        .with(alice()).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/my-purchases"))
                .andExpect(flash().attribute("message", "Purchase confirmed: 2 x Keyboard for $159.98."))
                .andExpect(flash().attributeExists("confirmedPurchaseId"));

        assertThat(stockOf(keyboard)).isEqualTo(8);
        assertThat(purchaseRepository.count()).isEqualTo(1);

        mockMvc.perform(get("/my-purchases").with(alice()))
                .andExpect(status().isOk())
                .andExpect(view().name("purchases/history"))
                .andExpect(model().attribute("purchases", hasSize(1)))
                .andExpect(model().attribute("totalSpent", comparesEqualTo(new BigDecimal("159.98"))))
                .andExpect(content().string(containsString("Keyboard")))
                .andExpect(content().string(containsString("$79.99")))
                .andExpect(content().string(containsString("$159.98")));

        // The catalog now shows the reduced stock.
        mockMvc.perform(get("/products"))
                .andExpect(content().string(containsString("8 in stock")));
    }

    @Test
    @DisplayName("the confirmation banner and the highlighted row render on the history page")
    void confirmationIsRenderedOnHistoryPage() throws Exception {
        Long purchaseId = purchaseService.purchase("alice", keyboard.getId(), 1).getId();

        mockMvc.perform(get("/my-purchases").with(alice())
                        .flashAttr("message", "Purchase confirmed: 1 x Keyboard for $79.99.")
                        .flashAttr("confirmedPurchaseId", purchaseId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Purchase confirmed: 1 x Keyboard for $79.99.")))
                .andExpect(content().string(containsString("table-success")));
    }

    @Test
    @DisplayName("asking for more than is in stock shows a clear error and buys nothing")
    void insufficientStockShowsAClearError() throws Exception {
        String expected = "Only 1 of Webcam left in stock, so an order for 5 could not be placed. Nothing was purchased.";

        mockMvc.perform(post("/products/{id}/purchase", webcam.getId()).param("quantity", "5")
                        .with(alice()).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/products/" + webcam.getId()))
                .andExpect(flash().attribute("error", expected));

        assertThat(stockOf(webcam)).isEqualTo(1);
        assertThat(purchaseRepository.count()).isZero();

        // The product page the buyer is sent back to displays that message.
        mockMvc.perform(get("/products/{id}", webcam.getId()).with(alice()).flashAttr("error", expected))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(expected)))
                .andExpect(content().string(containsString("alert-danger")));
    }

    @Test
    @DisplayName("a purchase request for an out-of-stock product is refused on the server, form or no form")
    void outOfStockPurchaseIsRefused() throws Exception {
        mockMvc.perform(post("/products/{id}/purchase", stand.getId()).param("quantity", "1")
                        .with(alice()).with(csrf()))
                .andExpect(redirectedUrl("/products/" + stand.getId()))
                .andExpect(flash().attribute("error", containsString("Laptop Stand is out of stock")));

        assertThat(purchaseRepository.count()).isZero();
    }

    @Test
    @DisplayName("the last unit can be bought once; the next buyer is told it is out of stock")
    void lastUnitSellsOnce() throws Exception {
        mockMvc.perform(post("/products/{id}/purchase", webcam.getId()).param("quantity", "1")
                        .with(alice()).with(csrf()))
                .andExpect(redirectedUrl("/my-purchases"));

        mockMvc.perform(post("/products/{id}/purchase", webcam.getId()).param("quantity", "1")
                        .with(alice()).with(csrf()))
                .andExpect(redirectedUrl("/products/" + webcam.getId()))
                .andExpect(flash().attribute("error", containsString("Webcam is out of stock")));

        assertThat(stockOf(webcam)).isZero();
        assertThat(purchaseRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("an invalid quantity redisplays the product page with a field error")
    void invalidQuantityRedisplaysTheForm() throws Exception {
        mockMvc.perform(post("/products/{id}/purchase", keyboard.getId()).param("quantity", "0")
                        .with(alice()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("products/detail"))
                .andExpect(model().attributeHasFieldErrors("purchaseForm", "quantity"))
                .andExpect(content().string(containsString("Quantity must be at least 1")));

        mockMvc.perform(post("/products/{id}/purchase", keyboard.getId()).param("quantity", "lots")
                        .with(alice()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("purchaseForm", "quantity"))
                .andExpect(content().string(containsString("Enter a whole number")));

        mockMvc.perform(post("/products/{id}/purchase", keyboard.getId()).param("quantity", "")
                        .with(alice()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Quantity is required")));

        assertThat(stockOf(keyboard)).isEqualTo(10);
        assertThat(purchaseRepository.count()).isZero();
    }

    @Test
    @DisplayName("a purchase request for an inactive product answers 404")
    void purchaseOfInactiveProductIsNotFound() throws Exception {
        mockMvc.perform(post("/products/{id}/purchase", retired.getId()).param("quantity", "1")
                        .with(alice()).with(csrf()))
                .andExpect(status().isNotFound());

        assertThat(purchaseRepository.count()).isZero();
    }

    @Test
    @DisplayName("an empty purchase history says so")
    void emptyHistoryRenders() throws Exception {
        mockMvc.perform(get("/my-purchases").with(alice()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("You have not bought anything yet")));
    }

    // --- owner: inventory ---------------------------------------------------

    @Test
    @DisplayName("the inventory lists every product, including inactive ones")
    void inventoryIncludesInactiveProducts() throws Exception {
        mockMvc.perform(get("/owner/products").with(owner()))
                .andExpect(status().isOk())
                .andExpect(view().name("owner/products"))
                .andExpect(model().attribute("products", hasSize(4)))
                .andExpect(content().string(containsString("Retired Gadget")))
                .andExpect(content().string(containsString("Inactive")))
                .andExpect(content().string(containsString("/owner/products/" + retired.getId() + "/reactivate")))
                .andExpect(content().string(containsString("/owner/products/" + keyboard.getId() + "/deactivate")));
    }

    @Test
    @DisplayName("the owner creates a product and it appears in the catalog")
    void ownerCreatesAProduct() throws Exception {
        mockMvc.perform(get("/owner/products/new").with(owner()))
                .andExpect(status().isOk())
                .andExpect(view().name("owner/product-form"))
                .andExpect(content().string(containsString("Add Product")));

        mockMvc.perform(post("/owner/products").with(owner()).with(csrf())
                        .param("name", "  Desk Lamp  ")
                        .param("description", "An adjustable LED lamp")
                        .param("price", "27.50")
                        .param("stock", "15"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/owner/products"))
                .andExpect(flash().attribute("message", "Added Desk Lamp."));

        Product lamp = productRepository.findAll().stream()
                .filter(p -> p.getName().equals("Desk Lamp")).findFirst().orElseThrow();
        assertThat(lamp.getDescription()).isEqualTo("An adjustable LED lamp");
        assertThat(lamp.getPrice()).isEqualByComparingTo("27.50");
        assertThat(lamp.getStock()).isEqualTo(15);
        assertThat(lamp.isActive()).isTrue();

        mockMvc.perform(get("/products")).andExpect(content().string(containsString("Desk Lamp")));
    }

    @Test
    @DisplayName("an invalid product is rejected with field errors and nothing is saved")
    void invalidProductIsRejected() throws Exception {
        mockMvc.perform(post("/owner/products").with(owner()).with(csrf())
                        .param("name", "")
                        .param("description", "")
                        .param("price", "-1.00")
                        .param("stock", "-3"))
                .andExpect(status().isOk())
                .andExpect(view().name("owner/product-form"))
                .andExpect(model().attributeHasFieldErrors("productForm", "name", "description", "price", "stock"))
                .andExpect(content().string(containsString("Name is required")))
                .andExpect(content().string(containsString("Price cannot be negative")))
                .andExpect(content().string(containsString("Stock cannot be negative")));

        mockMvc.perform(post("/owner/products").with(owner()).with(csrf())
                        .param("name", "Thing")
                        .param("description", "A thing")
                        .param("price", "nine dollars")
                        .param("stock", "1.5"))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("productForm", "price", "stock"))
                .andExpect(content().string(containsString("Enter a number, such as 19.99")));

        mockMvc.perform(post("/owner/products").with(owner()).with(csrf())
                        .param("name", "Thing")
                        .param("description", "A thing")
                        .param("price", "9.999")
                        .param("stock", "1"))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("productForm", "price"));

        assertThat(productRepository.count()).isEqualTo(4);
    }

    @Test
    @DisplayName("the owner edits a product's name, description, price and stock")
    void ownerEditsAProduct() throws Exception {
        mockMvc.perform(get("/owner/products/{id}/edit", keyboard.getId()).with(owner()))
                .andExpect(status().isOk())
                .andExpect(view().name("owner/product-form"))
                .andExpect(content().string(containsString("Edit Product")))
                .andExpect(content().string(containsString("value=\"Keyboard\"")))
                .andExpect(content().string(containsString("value=\"79.99\"")))
                .andExpect(content().string(containsString("A test keyboard")));

        mockMvc.perform(post("/owner/products/{id}", keyboard.getId()).with(owner()).with(csrf())
                        .param("name", "Mechanical Keyboard")
                        .param("description", "Now with brown switches")
                        .param("price", "89.00")
                        .param("stock", "40"))
                .andExpect(redirectedUrl("/owner/products"))
                .andExpect(flash().attribute("message", "Updated Mechanical Keyboard."));

        Product edited = productRepository.findById(keyboard.getId()).orElseThrow();
        assertThat(edited.getName()).isEqualTo("Mechanical Keyboard");
        assertThat(edited.getDescription()).isEqualTo("Now with brown switches");
        assertThat(edited.getPrice()).isEqualByComparingTo("89.00");
        assertThat(edited.getStock()).isEqualTo(40);
        assertThat(productRepository.count()).isEqualTo(4);
    }

    @Test
    @DisplayName("editing an unknown product answers 404")
    void editingUnknownProductIsNotFound() throws Exception {
        mockMvc.perform(get("/owner/products/{id}/edit", 999_999).with(owner()))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/owner/products/{id}", 999_999).with(owner()).with(csrf())
                        .param("name", "Ghost").param("description", "x").param("price", "1.00").param("stock", "1"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("deactivating hides a product from the catalog and keeps its purchases; reactivating brings it back")
    void ownerDeactivatesAndReactivates() throws Exception {
        purchaseService.purchase("alice", keyboard.getId(), 1);

        mockMvc.perform(post("/owner/products/{id}/deactivate", keyboard.getId()).with(owner()).with(csrf()))
                .andExpect(redirectedUrl("/owner/products"))
                .andExpect(flash().attribute("message", containsString("Deactivated Keyboard")));

        assertThat(productRepository.findById(keyboard.getId()).orElseThrow().isActive()).isFalse();
        mockMvc.perform(get("/products")).andExpect(content().string(not(containsString("A test keyboard"))));
        mockMvc.perform(get("/products/{id}", keyboard.getId())).andExpect(status().isNotFound());

        // The row is still there, so the purchase that refers to it is intact on both sides.
        mockMvc.perform(get("/my-purchases").with(alice()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Keyboard")))
                .andExpect(content().string(not(containsString("href=\"/products/" + keyboard.getId() + "\""))));
        mockMvc.perform(get("/owner/sales").with(owner()))
                .andExpect(content().string(containsString("Keyboard (inactive)")));

        mockMvc.perform(post("/owner/products/{id}/reactivate", keyboard.getId()).with(owner()).with(csrf()))
                .andExpect(redirectedUrl("/owner/products"))
                .andExpect(flash().attribute("message", containsString("Reactivated Keyboard")));

        assertThat(productRepository.findById(keyboard.getId()).orElseThrow().isActive()).isTrue();
        mockMvc.perform(get("/products")).andExpect(content().string(containsString("A test keyboard")));
    }

    // --- owner: sales -------------------------------------------------------

    @Test
    @DisplayName("the sales page lists every purchase with buyer, product, quantity, prices and a revenue total")
    void salesPageListsPurchasesAndTotalsRevenue() throws Exception {
        purchaseService.purchase("alice", keyboard.getId(), 2);   // 159.98
        purchaseService.purchase("alice", webcam.getId(), 1);     //  49.95

        mockMvc.perform(get("/owner/sales").with(owner()))
                .andExpect(status().isOk())
                .andExpect(view().name("owner/sales"))
                .andExpect(model().attribute("sales", hasSize(2)))
                .andExpect(model().attribute("revenueTotal", comparesEqualTo(new BigDecimal("209.93"))))
                .andExpect(model().attribute("unitsSold", 3))
                .andExpect(content().string(containsString("alice")))
                .andExpect(content().string(containsString("Keyboard")))
                .andExpect(content().string(containsString("Webcam")))
                .andExpect(content().string(containsString("$79.99")))
                .andExpect(content().string(containsString("$159.98")))
                .andExpect(content().string(containsString("$209.93")));
    }

    @Test
    @DisplayName("the sales page filters by product and by date range")
    void salesPageFilters() throws Exception {
        purchaseService.purchase("alice", keyboard.getId(), 2);   // 159.98
        purchaseService.purchase("alice", webcam.getId(), 1);     //  49.95
        String today = LocalDate.now().toString();
        String yesterday = LocalDate.now().minusDays(1).toString();
        String tomorrow = LocalDate.now().plusDays(1).toString();

        mockMvc.perform(get("/owner/sales").param("productId", keyboard.getId().toString()).with(owner()))
                .andExpect(status().isOk())
                .andExpect(model().attribute("sales", hasSize(1)))
                .andExpect(model().attribute("revenueTotal", comparesEqualTo(new BigDecimal("159.98"))))
                .andExpect(content().string(containsString("selected=\"selected\"")));

        mockMvc.perform(get("/owner/sales").param("from", today).param("to", today).with(owner()))
                .andExpect(model().attribute("sales", hasSize(2)))
                .andExpect(model().attribute("revenueTotal", comparesEqualTo(new BigDecimal("209.93"))))
                .andExpect(content().string(containsString("value=\"" + today + "\"")));

        mockMvc.perform(get("/owner/sales").param("productId", webcam.getId().toString())
                        .param("from", yesterday).param("to", tomorrow).with(owner()))
                .andExpect(model().attribute("sales", hasSize(1)))
                .andExpect(model().attribute("revenueTotal", comparesEqualTo(new BigDecimal("49.95"))));

        mockMvc.perform(get("/owner/sales").param("to", yesterday).with(owner()))
                .andExpect(status().isOk())
                .andExpect(model().attribute("sales", hasSize(0)))
                .andExpect(model().attribute("revenueTotal", comparesEqualTo(BigDecimal.ZERO)))
                .andExpect(content().string(containsString("No purchases match these filters")));

        mockMvc.perform(get("/owner/sales").param("from", tomorrow).with(owner()))
                .andExpect(model().attribute("sales", hasSize(0)));

        // Blank parameters, as the form submits them when a filter is left empty, mean "no filter".
        mockMvc.perform(get("/owner/sales").param("productId", "").param("from", "").param("to", "").with(owner()))
                .andExpect(status().isOk())
                .andExpect(model().attribute("sales", hasSize(2)));
    }

    @Test
    @DisplayName("a date range that runs backwards is explained rather than silently empty")
    void backwardsDateRangeIsExplained() throws Exception {
        purchaseService.purchase("alice", keyboard.getId(), 1);

        mockMvc.perform(get("/owner/sales").param("from", "2026-10-10").param("to", "2026-10-01").with(owner()))
                .andExpect(status().isOk())
                .andExpect(model().attribute("sales", hasSize(0)))
                .andExpect(content().string(containsString("The From date is after the To date")));
    }

    @Test
    @DisplayName("with no purchases yet, the sales page says so and totals zero")
    void emptySalesPageRenders() throws Exception {
        mockMvc.perform(get("/owner/sales").with(owner()))
                .andExpect(status().isOk())
                .andExpect(model().attribute("revenueTotal", comparesEqualTo(BigDecimal.ZERO)))
                .andExpect(content().string(containsString("No purchases have been made yet")))
                .andExpect(content().string(containsString("$0.00")));
    }

    // --- helpers ------------------------------------------------------------

    private static RequestPostProcessor alice() {
        return user("alice").roles("BUYER");
    }

    private static RequestPostProcessor owner() {
        return user("shopowner").roles("OWNER");
    }

    private int stockOf(Product product) {
        return productRepository.findById(product.getId()).orElseThrow().getStock();
    }
}
