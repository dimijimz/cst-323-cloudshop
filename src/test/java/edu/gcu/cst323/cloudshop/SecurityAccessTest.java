package edu.gcu.cst323.cloudshop;

import edu.gcu.cst323.cloudshop.model.Product;
import edu.gcu.cst323.cloudshop.repository.ProductRepository;
import edu.gcu.cst323.cloudshop.repository.PurchaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Checks the access-control policy in {@code SecurityConfig} from the outside,
 * request by request: what the public can reach, what each role can and cannot
 * reach, and that a refused request really did change nothing.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"h2", "test"})
class SecurityAccessTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private PurchaseRepository purchaseRepository;

    private Product product;

    @BeforeEach
    void setUp() {
        purchaseRepository.deleteAll();
        productRepository.deleteAll();
        product = productRepository.save(new Product("Keyboard", "Test product", new BigDecimal("79.99"), 10));
    }

    // --- anonymous visitors -------------------------------------------------

    @ParameterizedTest(name = "anonymous GET {0} is sent to the login page")
    @ValueSource(strings = {"/owner/products", "/owner/products/new", "/owner/sales", "/my-purchases"})
    void anonymousUsersAreSentToLogin(String url) throws Exception {
        mockMvc.perform(get(url))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    @DisplayName("an anonymous purchase attempt is sent to the login page and buys nothing")
    void anonymousPurchaseIsSentToLogin() throws Exception {
        mockMvc.perform(post("/products/{id}/purchase", product.getId()).param("quantity", "1").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));

        assertNothingWasBought();
    }

    @Test
    @DisplayName("an anonymous visitor cannot change the inventory")
    void anonymousCannotChangeInventory() throws Exception {
        mockMvc.perform(post("/owner/products/{id}/deactivate", product.getId()).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));

        assertThat(productRepository.findById(product.getId()).orElseThrow().isActive()).isTrue();
    }

    @ParameterizedTest(name = "anonymous GET {0} is allowed")
    @ValueSource(strings = {"/", "/products", "/login", "/register", "/health", "/actuator/health", "/actuator/info"})
    void publicPagesNeedNoLogin(String url) throws Exception {
        mockMvc.perform(get(url)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("an anonymous visitor can open a product, but is offered sign-in instead of a purchase form")
    void anonymousCanViewAProductButNotBuy() throws Exception {
        mockMvc.perform(get("/products/{id}", product.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Keyboard")))
                .andExpect(content().string(containsString("Sign in")))
                .andExpect(content().string(not(containsString("name=\"quantity\""))));
    }

    // --- buyers -------------------------------------------------------------

    @ParameterizedTest(name = "a buyer's GET {0} is forbidden")
    @ValueSource(strings = {"/owner/products", "/owner/products/new", "/owner/products/1/edit", "/owner/sales"})
    void buyerCannotReachOwnerPages(String url) throws Exception {
        mockMvc.perform(get(url).with(buyer())).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a buyer cannot create, edit, deactivate or reactivate products")
    void buyerCannotChangeInventory() throws Exception {
        Long id = product.getId();

        mockMvc.perform(post("/owner/products").with(buyer()).with(csrf())
                        .param("name", "Injected").param("description", "x").param("price", "1.00").param("stock", "1"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/owner/products/{id}", id).with(buyer()).with(csrf())
                        .param("name", "Renamed").param("description", "x").param("price", "0.01").param("stock", "999"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/owner/products/{id}/deactivate", id).with(buyer()).with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/owner/products/{id}/reactivate", id).with(buyer()).with(csrf()))
                .andExpect(status().isForbidden());

        // The refusals were real: the one product is exactly as it was.
        assertThat(productRepository.count()).isEqualTo(1);
        Product unchanged = productRepository.findById(id).orElseThrow();
        assertThat(unchanged.getName()).isEqualTo("Keyboard");
        assertThat(unchanged.getPrice()).isEqualByComparingTo("79.99");
        assertThat(unchanged.getStock()).isEqualTo(10);
        assertThat(unchanged.isActive()).isTrue();
    }

    @Test
    @DisplayName("a buyer can reach their own purchase history, and sees no owner links")
    void buyerCanReachOwnPages() throws Exception {
        mockMvc.perform(get("/my-purchases").with(buyer()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("My Purchases")))
                .andExpect(content().string(not(containsString("/owner/"))));
    }

    // --- the owner ----------------------------------------------------------

    @ParameterizedTest(name = "the owner's GET {0} is allowed")
    @ValueSource(strings = {"/owner/products", "/owner/products/new", "/owner/sales"})
    void ownerCanReachOwnerPages(String url) throws Exception {
        mockMvc.perform(get(url).with(owner())).andExpect(status().isOk());
    }

    @Test
    @DisplayName("the owner cannot purchase or open a buyer's purchase history")
    void ownerCannotUseBuyerRoutes() throws Exception {
        mockMvc.perform(post("/products/{id}/purchase", product.getId()).param("quantity", "1")
                        .with(owner()).with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/my-purchases").with(owner()))
                .andExpect(status().isForbidden());

        assertNothingWasBought();
    }

    // --- CSRF ---------------------------------------------------------------

    @Test
    @DisplayName("a state-changing request without a CSRF token is refused, even from the right role")
    void postsWithoutCsrfTokenAreRefused() throws Exception {
        mockMvc.perform(post("/products/{id}/purchase", product.getId()).param("quantity", "1").with(buyer()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/owner/products/{id}/deactivate", product.getId()).with(owner()))
                .andExpect(status().isForbidden());

        assertNothingWasBought();
        assertThat(productRepository.findById(product.getId()).orElseThrow().isActive()).isTrue();
    }

    // --- health and actuator ------------------------------------------------

    @Test
    @DisplayName("/health answers UP without signing in")
    void livenessProbeIsPublic() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.application").value("cloudshop"));
    }

    @Test
    @DisplayName("/actuator/health reports status only, never component details, even to the owner")
    void actuatorHealthNeverShowsDetails() throws Exception {
        for (RequestPostProcessor who : new RequestPostProcessor[] {request -> request, buyer(), owner()}) {
            mockMvc.perform(get("/actuator/health").with(who))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"))
                    .andExpect(jsonPath("$.components").doesNotExist())
                    .andExpect(jsonPath("$.details").doesNotExist());
        }
    }

    @ParameterizedTest(name = "{0} is not exposed")
    @ValueSource(strings = {"/actuator/env", "/actuator/beans", "/actuator/metrics", "/actuator/configprops",
            "/actuator/loggers", "/actuator/mappings", "/actuator/heapdump"})
    void onlyHealthAndInfoAreExposed(String url) throws Exception {
        // Signed in as the owner, so a 404 means "no such endpoint", not "not allowed".
        mockMvc.perform(get(url).with(owner())).andExpect(status().isNotFound());
        // And an anonymous caller is not told even that much.
        mockMvc.perform(get(url)).andExpect(status().is3xxRedirection());
    }

    // --- helpers ------------------------------------------------------------

    private static RequestPostProcessor buyer() {
        return user("alice").roles("BUYER");
    }

    private static RequestPostProcessor owner() {
        return user("shopowner").roles("OWNER");
    }

    private void assertNothingWasBought() {
        assertThat(purchaseRepository.count()).isZero();
        assertThat(productRepository.findById(product.getId()).orElseThrow().getStock()).isEqualTo(10);
    }
}
