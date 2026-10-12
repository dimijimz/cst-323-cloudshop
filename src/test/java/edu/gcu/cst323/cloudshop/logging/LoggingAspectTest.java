package edu.gcu.cst323.cloudshop.logging;

import ch.qos.logback.classic.Level;
import edu.gcu.cst323.cloudshop.form.ProductForm;
import edu.gcu.cst323.cloudshop.form.RegistrationForm;
import edu.gcu.cst323.cloudshop.model.Product;
import edu.gcu.cst323.cloudshop.model.Purchase;
import edu.gcu.cst323.cloudshop.model.Role;
import edu.gcu.cst323.cloudshop.repository.ProductRepository;
import edu.gcu.cst323.cloudshop.repository.PurchaseRepository;
import edu.gcu.cst323.cloudshop.repository.UserRepository;
import edu.gcu.cst323.cloudshop.service.ProductService;
import edu.gcu.cst323.cloudshop.service.PurchaseService;
import edu.gcu.cst323.cloudshop.service.UserService;
import org.aspectj.lang.annotation.Pointcut;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.aop.PointcutAdvisor;
import org.springframework.aop.aspectj.AbstractAspectJAdvice;
import org.springframework.aop.aspectj.AspectJExpressionPointcut;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves what {@link LoggingAspect} writes: ENTER, EXIT and EXCEPTION lines in
 * their exact formats, at the right levels, through the right loggers, for all
 * three layers.
 *
 * <p>The lines are compared as {@code LEVEL LoggerClass - message}, which
 * {@link LogCapture} builds from the real log events, with durations normalised
 * to {@code [N ms]}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"h2", "test"})
@ExtendWith(OutputCaptureExtension.class)
class LoggingAspectTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserService userService;

    @Autowired
    private ProductService productService;

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
    private LogCapture log;

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

        // Started last, so the lines the fixtures produced are not among those asserted on.
        log = LogCapture.start(Level.DEBUG);
    }

    @AfterEach
    void tearDown() {
        log.close();
    }

    // --- ENTER and EXIT -----------------------------------------------------

    @Test
    @DisplayName("one request writes ENTER and EXIT for the controller, the service and the repository, each through its own logger at DEBUG")
    void enterAndExitAreLoggedForEveryLayer() throws Exception {
        mockMvc.perform(get("/products")).andExpect(status().isOk());

        assertThat(log.lines()).containsExactly(
                "DEBUG CatalogController - ENTER CatalogController.list(model=<Model>)",
                "DEBUG ProductService - ENTER ProductService.findCatalog()",
                "DEBUG ProductRepository - ENTER ProductRepository.findByActiveTrueOrderByNameAsc()",
                "DEBUG ProductRepository - EXIT ProductRepository.findByActiveTrueOrderByNameAsc -> <List size=2> [N ms]",
                "DEBUG ProductService - EXIT ProductService.findCatalog -> <List size=2> [N ms]",
                "DEBUG CatalogController - EXIT CatalogController.list -> <String> [N ms]");
    }

    @Test
    @DisplayName("the lines reach stdout in the configured pattern, with an ISO-8601 timestamp that carries its offset")
    void linesReachStdoutInTheConfiguredPattern(CapturedOutput output) throws Exception {
        mockMvc.perform(get("/products")).andExpect(status().isOk());

        String timestamp = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}(Z|[+-]\\d{2}:\\d{2})";
        assertThat(output.getOut().lines())
                .anyMatch(line -> line.matches(timestamp
                        + " DEBUG \\[.+] e\\.g\\.c\\.c\\.controller\\.CatalogController"
                        + " - ENTER CatalogController\\.list\\(model=<Model>\\)"))
                .anyMatch(line -> line.matches(timestamp
                        + " DEBUG \\[.+] e\\.g\\.c\\.c\\.repository\\.ProductRepository"
                        + " - EXIT ProductRepository\\.findByActiveTrueOrderByNameAsc -> <List size=2> \\[\\d+ ms]"));
        // Console means stdout: nothing is written to stderr.
        assertThat(output.getErr()).doesNotContain("CatalogController");
    }

    @Test
    @DisplayName("with the level at INFO, the default, there are no ENTER or EXIT lines but exceptions are still logged")
    void atInfoOnlyExceptionsAreLogged() throws Exception {
        log.setLevel(Level.INFO);

        mockMvc.perform(get("/products")).andExpect(status().isOk());
        mockMvc.perform(get("/products/{id}", 999_999)).andExpect(status().isNotFound());

        String what = "[ERR-NOTFOUND] ResourceNotFoundException: Product 999999 was not found";
        assertThat(log.lines()).containsExactly(
                "WARN ProductService - EXCEPTION ProductService.findActiveById " + what,
                "WARN CatalogController - EXCEPTION CatalogController.detail " + what,
                "WARN GlobalExceptionHandler - HANDLED 404 GET /products/{id} " + what);
    }

    // --- repositories are Spring Data proxies -------------------------------

    @Test
    @DisplayName("every repository bean is a proxy carrying the logging advice, and the advice matches every public method, inherited ones included")
    void repositoriesAreAdvisedOnEveryPublicMethod() throws Exception {
        Map<Class<?>, Object> repositories = Map.of(
                UserRepository.class, userRepository,
                ProductRepository.class, productRepository,
                PurchaseRepository.class, purchaseRepository);

        for (Map.Entry<Class<?>, Object> repository : repositories.entrySet()) {
            Class<?> repositoryInterface = repository.getKey();
            Object bean = repository.getValue();

            // Not a class from this application at all: a JDK proxy generated at startup.
            assertThat(AopUtils.isJdkDynamicProxy(bean)).as("%s is a JDK proxy", repositoryInterface.getSimpleName()).isTrue();
            assertThat(bean.getClass().getPackageName()).doesNotStartWith("edu.gcu");

            Advised advised = (Advised) bean;
            PointcutAdvisor logging = loggingAdvisorOf(advised);

            // getMethods() includes everything inherited from JpaRepository and its parents.
            Method[] publicMethods = repositoryInterface.getMethods();
            assertThat(publicMethods).extracting(Method::getName)
                    .contains("save", "findById", "findAll", "count", "deleteAll", "saveAndFlush");
            for (Method method : publicMethods) {
                assertThat(logging.getPointcut().getMethodMatcher().matches(method, advised.getTargetClass()))
                        .as("logging advice matches %s", method)
                        .isTrue();
            }
            assertThat(logging.getPointcut().getMethodMatcher()
                    .matches(Object.class.getMethod("toString"), advised.getTargetClass()))
                    .as("toString is left alone")
                    .isFalse();
        }
    }

    @Test
    @DisplayName("the repository pointcut only matches because of its trailing +; without it the proxies would not be advised at all")
    void repositoryPointcutNeedsItsSubtypeMarker() throws Exception {
        String expression = LoggingAspect.class.getMethod("repositoryLayer").getAnnotation(Pointcut.class).value();
        assertThat(expression).isEqualTo("within(edu.gcu.cst323.cloudshop.repository..*+)");
        String withoutSubtypes = "within(edu.gcu.cst323.cloudshop.repository..*)";

        for (Object bean : new Object[] {userRepository, productRepository, purchaseRepository}) {
            assertThat(AopUtils.canApply(pointcut(expression), bean.getClass())).isTrue();
            assertThat(AopUtils.canApply(pointcut(withoutSubtypes), bean.getClass())).isFalse();
        }
    }

    @Test
    @DisplayName("inherited, derived and @Query repository methods are all logged, under the repository interface's own logger")
    void repositoryMethodsOfEveryKindAreLogged() {
        productRepository.count();                           // inherited from CrudRepository
        productRepository.findById(keyboard.getId());        // inherited, with an argument
        productRepository.findByIdAndActiveTrue(999_999L);   // derived from its name
        userRepository.existsByRole(Role.OWNER);             // derived, with an enum argument
        purchaseRepository.findSales(null, null, null);      // @Query
        purchaseRepository.deleteAll();                      // inherited, returns nothing

        assertThat(log.lines()).containsExactly(
                "DEBUG ProductRepository - ENTER ProductRepository.count()",
                "DEBUG ProductRepository - EXIT ProductRepository.count -> <long> [N ms]",
                "DEBUG ProductRepository - ENTER ProductRepository.findById(id=" + keyboard.getId() + ")",
                "DEBUG ProductRepository - EXIT ProductRepository.findById -> <Optional of Product> [N ms]",
                "DEBUG ProductRepository - ENTER ProductRepository.findByIdAndActiveTrue(id=999999)",
                "DEBUG ProductRepository - EXIT ProductRepository.findByIdAndActiveTrue -> <Optional empty> [N ms]",
                "DEBUG UserRepository - ENTER UserRepository.existsByRole(role=OWNER)",
                "DEBUG UserRepository - EXIT UserRepository.existsByRole -> <boolean> [N ms]",
                "DEBUG PurchaseRepository - ENTER PurchaseRepository.findSales(productId=null, from=null, to=null)",
                "DEBUG PurchaseRepository - EXIT PurchaseRepository.findSales -> <List size=0> [N ms]",
                "DEBUG PurchaseRepository - ENTER PurchaseRepository.deleteAll()",
                "DEBUG PurchaseRepository - EXIT PurchaseRepository.deleteAll -> void [N ms]");
    }

    @Test
    @DisplayName("toString, equals and hashCode on a repository are not traced")
    void objectMethodsAreNotLogged() {
        assertThat(productRepository.toString()).isNotEmpty();
        assertThat(productRepository.equals(productRepository)).isTrue();
        assertThat(productRepository.hashCode()).isEqualTo(productRepository.hashCode());

        assertThat(log.lines()).isEmpty();
    }

    // --- arguments and results ----------------------------------------------

    @Test
    @DisplayName("arguments are printed only when they are numbers, booleans, enums or dates; everything else is reduced to its type")
    void argumentsAreMaskedByType() throws Exception {
        Long id = keyboard.getId();

        mockMvc.perform(post("/products/{id}/purchase", id).param("quantity", "2").with(alice()).with(csrf()))
                .andExpect(redirectedUrl("/my-purchases"));
        mockMvc.perform(get("/owner/sales").param("productId", id.toString()).param("from", "2026-01-31").with(owner()))
                .andExpect(status().isOk());

        assertThat(log.lines()).contains(
                "DEBUG PurchaseController - ENTER PurchaseController.purchase(id=" + id + ", form=<PurchaseForm>, "
                        + "binding=<BindingResult>, principal=<Principal>, model=<Model>, redirectAttributes=<RedirectAttributes>)",
                "DEBUG PurchaseService - ENTER PurchaseService.purchase(username=<String>, productId=" + id + ", quantity=2)",
                "DEBUG ProductRepository - ENTER ProductRepository.decrementStock(id=" + id + ", quantity=2)",
                "DEBUG UserRepository - ENTER UserRepository.findByUsernameIgnoreCase(username=<String>)",
                "DEBUG PurchaseRepository - ENTER PurchaseRepository.save(entity=<Purchase>)",
                "DEBUG SalesController - ENTER SalesController.sales(productId=" + id + ", from=2026-01-31, to=null, model=<Model>)",
                "DEBUG PurchaseService - ENTER PurchaseService.findSales(productId=" + id + ", from=2026-01-31, to=null)",
                "DEBUG PurchaseRepository - ENTER PurchaseRepository.findSales(productId=" + id + ", from=2026-01-31T00:00, to=null)");
        // The buyer and the product appear in the log as ids only.
        assertThat(log.everything()).doesNotContain("alice", "Keyboard");
    }

    @Test
    @DisplayName("results are logged as their type, with the size for a collection, and never as their value")
    void resultsAreLoggedAsTypes() throws Exception {
        mockMvc.perform(get("/health")).andExpect(status().isOk());
        productService.findById(keyboard.getId());
        userService.usernameTaken("alice");

        assertThat(log.lines()).contains(
                "DEBUG HealthController - EXIT HealthController.health -> <Map size=3> [N ms]",
                "DEBUG ProductService - EXIT ProductService.findById -> <Product> [N ms]",
                "DEBUG UserService - EXIT UserService.usernameTaken -> <boolean> [N ms]");
        assertThat(log.everything()).doesNotContain("alice", "Keyboard", "79.99");
    }

    // --- EXCEPTION ----------------------------------------------------------

    @Test
    @DisplayName("a missing product is one WARN line per layer with ERR-NOTFOUND, no EXIT line and no stack trace")
    void notFoundIsLoggedAtWarnWithItsCode() throws Exception {
        mockMvc.perform(get("/products/{id}", 999_999)).andExpect(status().isNotFound());

        String what = "[ERR-NOTFOUND] ResourceNotFoundException: Product 999999 was not found";
        assertThat(log.lines()).containsExactly(
                "DEBUG CatalogController - ENTER CatalogController.detail(id=999999, model=<Model>)",
                "DEBUG ProductService - ENTER ProductService.findActiveById(id=999999)",
                "DEBUG ProductRepository - ENTER ProductRepository.findByIdAndActiveTrue(id=999999)",
                "DEBUG ProductRepository - EXIT ProductRepository.findByIdAndActiveTrue -> <Optional empty> [N ms]",
                "WARN ProductService - EXCEPTION ProductService.findActiveById " + what,
                "WARN CatalogController - EXCEPTION CatalogController.detail " + what,
                "WARN GlobalExceptionHandler - HANDLED 404 GET /products/{id} " + what);
        assertThat(log.eventsWithStackTrace()).isEmpty();
    }

    @Test
    @DisplayName("an order for more than is in stock is one WARN line per layer with ERR-STOCK")
    void insufficientStockIsLoggedAtWarnWithItsCode() throws Exception {
        mockMvc.perform(post("/products/{id}/purchase", webcam.getId()).param("quantity", "5").with(alice()).with(csrf()))
                .andExpect(redirectedUrl("/products/" + webcam.getId()));

        String what = "[ERR-STOCK] InsufficientStockException: Only 1 of Webcam left in stock, "
                + "so an order for 5 could not be placed. Nothing was purchased.";
        assertThat(log.lines()).containsSubsequence(
                "WARN PurchaseService - EXCEPTION PurchaseService.purchase " + what,
                "WARN PurchaseController - EXCEPTION PurchaseController.purchase " + what,
                "WARN GlobalExceptionHandler - HANDLED 302 POST /products/{id}/purchase " + what);
        assertThat(log.lines()).noneMatch(line -> line.contains("EXIT PurchaseService.purchase"));
        assertThat(log.eventsWithStackTrace()).isEmpty();
    }

    @Test
    @DisplayName("a binding or validation failure is a WARN line with ERR-VALIDATION that names the field and never the value sent")
    void validationFailuresCarryTheirCodeAndNeverTheRejectedValue() throws Exception {
        mockMvc.perform(get("/products/{id}", "rejected-id-canary")).andExpect(status().isBadRequest());
        mockMvc.perform(post("/register").with(csrf())
                        .param("username", "bob")
                        .param("email", "rejected-email-canary")
                        .param("password", "rejected-password-canary")
                        .param("confirmPassword", "rejected-confirmation-canary"))
                .andExpect(status().isOk());

        assertThat(log.lines()).contains(
                "WARN GlobalExceptionHandler - HANDLED 400 GET /products/{id} [ERR-VALIDATION] "
                        + "MethodArgumentTypeMismatchException: parameter 'id' could not be converted to Long",
                "WARN AuthController - REJECTED POST /register [ERR-VALIDATION] "
                        + "2 validation error(s) on [confirmPassword, email]");
        assertThat(log.everything()).doesNotContain("canary");
        assertThat(log.eventsWithStackTrace()).isEmpty();
    }

    @Test
    @DisplayName("an unknown username at sign-in is a WARN line with ERR-NOTFOUND, and what was typed is not repeated")
    void unknownUsernameAtSignInIsNotEchoed() throws Exception {
        // The classic slip: a password typed into the username box.
        mockMvc.perform(formLogin("/login").user("password-in-the-wrong-box-canary").password("whatever"))
                .andExpect(unauthenticated());

        assertThat(log.lines()).contains(
                "DEBUG UserService - ENTER UserService.loadUserByUsername(username=<String>)",
                "WARN UserService - EXCEPTION UserService.loadUserByUsername [ERR-NOTFOUND] "
                        + "UsernameNotFoundException: No account has that username");
        assertThat(log.everything()).doesNotContain("canary");
        assertThat(log.eventsWithStackTrace()).isEmpty();
    }

    @Test
    @DisplayName("an unexpected exception is an ERROR with its stack trace where it is first seen, and one ERROR line with no trace in the layer above")
    void unexpectedExceptionIsTracedOnceWhereFirstSeen() {
        assertThatThrownBy(() -> productService.findById(null))
                .isInstanceOf(InvalidDataAccessApiUsageException.class);

        String what = "[ERR-UNEXPECTED] InvalidDataAccessApiUsageException: The given id must not be null";
        assertThat(log.lines()).containsExactly(
                "DEBUG ProductService - ENTER ProductService.findById(id=null)",
                "DEBUG ProductRepository - ENTER ProductRepository.findById(id=null)",
                "ERROR ProductRepository - EXCEPTION ProductRepository.findById " + what,
                "ERROR ProductService - EXCEPTION ProductService.findById " + what);
        assertThat(log.eventsWithStackTrace()).singleElement().satisfies(event -> {
            assertThat(event.getLoggerName()).isEqualTo(ProductRepository.class.getName());
            assertThat(event.getThrowableProxy().getClassName())
                    .isEqualTo(InvalidDataAccessApiUsageException.class.getName());
        });
    }

    @Test
    @DisplayName("errors Spring MVC answers itself keep their status and are not logged as unexpected")
    void frameworkErrorsKeepTheirStatus() throws Exception {
        mockMvc.perform(get("/no-such-page").with(owner())).andExpect(status().isNotFound());
        mockMvc.perform(post("/products").with(alice()).with(csrf())).andExpect(status().isMethodNotAllowed());

        assertThat(log.lines()).noneMatch(line -> line.contains("HANDLED") || line.contains("ERR-UNEXPECTED"));
        assertThat(log.eventsWithStackTrace()).isEmpty();
    }

    // --- business events ----------------------------------------------------

    @Test
    @DisplayName("at INFO the log holds the business events: purchase completed, product created, price changed, each by id")
    void businessEventsAreLoggedAtInfo() {
        log.setLevel(Level.INFO);
        Long buyerId = userRepository.findByUsernameIgnoreCase("alice").orElseThrow().getId();

        Purchase purchase = purchaseService.purchase("alice", keyboard.getId(), 2);

        ProductForm form = new ProductForm();
        form.setName("Desk Lamp");
        form.setDescription("An adjustable LED lamp");
        form.setPrice(new BigDecimal("27.50"));
        form.setStock(15);
        Long lampId = productService.create(form).getId();

        form.setPrice(new BigDecimal("29.00"));
        productService.update(lampId, form);
        // Same price, new stock: an update, but not a price change.
        form.setStock(20);
        productService.update(lampId, form);

        assertThat(log.lines()).containsExactly(
                "INFO PurchaseService - Purchase completed: purchaseId=" + purchase.getId() + " buyerId=" + buyerId
                        + " productId=" + keyboard.getId() + " quantity=2 unitPrice=79.99 total=159.98 stockLeft=8",
                "INFO ProductService - Product created: productId=" + lampId + " price=27.50 stock=15",
                "INFO ProductService - Product updated: productId=" + lampId + " price=29.00 stock=15",
                "INFO ProductService - Price changed: productId=" + lampId + " oldPrice=27.50 newPrice=29.00",
                "INFO ProductService - Product updated: productId=" + lampId + " price=29.00 stock=20");
        assertThat(log.everything()).doesNotContain("alice", "Keyboard", "Desk Lamp");
    }

    // --- helpers ------------------------------------------------------------

    private static PointcutAdvisor loggingAdvisorOf(Advised advised) {
        return Arrays.stream(advised.getAdvisors())
                .filter(PointcutAdvisor.class::isInstance)
                .map(PointcutAdvisor.class::cast)
                .filter(advisor -> advisor.getAdvice() instanceof AbstractAspectJAdvice advice
                        && "loggingAspect".equals(advice.getAspectName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no LoggingAspect advice on " + advised.getTargetClass()));
    }

    private static AspectJExpressionPointcut pointcut(String expression) {
        AspectJExpressionPointcut pointcut = new AspectJExpressionPointcut();
        pointcut.setExpression(expression);
        return pointcut;
    }

    private static RequestPostProcessor alice() {
        return user("alice").roles("BUYER");
    }

    private static RequestPostProcessor owner() {
        return user("shopowner").roles("OWNER");
    }
}
