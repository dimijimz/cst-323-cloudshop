package edu.gcu.cst323.cloudshop.logging;

import ch.qos.logback.classic.Level;
import edu.gcu.cst323.cloudshop.exception.ResourceNotFoundException;
import edu.gcu.cst323.cloudshop.model.Product;
import edu.gcu.cst323.cloudshop.model.User;
import edu.gcu.cst323.cloudshop.repository.ProductRepository;
import edu.gcu.cst323.cloudshop.repository.PurchaseRepository;
import edu.gcu.cst323.cloudshop.repository.UserRepository;
import edu.gcu.cst323.cloudshop.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Drives every flow that handles a password, a username or an email address,
 * with the log at its most verbose, then searches everything that was logged for
 * those values.
 *
 * <p>Each value is a made-up string that could not occur by chance, so finding
 * one anywhere means it leaked. The search covers both the log events, including
 * the messages inside stack traces, and what was actually written to the console.
 *
 * <p>Runs against its own in-memory database, because it creates an owner account
 * that the other test classes assume does not exist.
 */
@SpringBootTest(properties =
        "spring.datasource.url=jdbc:h2:mem:sensitivelogging;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles({"h2", "test"})
@ExtendWith(OutputCaptureExtension.class)
class SensitiveDataLoggingTest {

    private static final String USERNAME = "canary-user-7f3a";
    private static final String EMAIL = "canary-7f3a@example.com";
    private static final String PASSWORD = "Canary-Passw0rd-7f3a!";
    private static final String WRONG_PASSWORD = "Wrong-Canary-9b1c!";
    private static final String MISMATCHED_CONFIRMATION = "Mismatch-Canary-2d4e!";
    private static final String TOO_LONG_PASSWORD = "Too-Long-Canary-5a6b-" + "x".repeat(60);
    private static final String OTHER_USERNAME = "canary-other-6d7e";
    private static final String OTHER_EMAIL = "canary-other-6d7e@example.com";
    private static final String UNKNOWN_USERNAME = "canary-nobody-1a2b";
    private static final String OWNER_USERNAME = "canary-owner-8c9d";
    private static final String OWNER_EMAIL = "canary-owner-8c9d@example.com";
    private static final String OWNER_PASSWORD = "Owner-Canary-3e4f!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private PurchaseRepository purchaseRepository;

    private Product product;
    private LogCapture log;

    @BeforeEach
    void setUp() {
        purchaseRepository.deleteAll();
        productRepository.deleteAll();
        userRepository.deleteAll();
        product = productRepository.save(new Product("Keyboard", "A test keyboard", new BigDecimal("79.99"), 10));

        // TRACE: everything the application is capable of logging.
        log = LogCapture.start(Level.TRACE);
    }

    @AfterEach
    void tearDown() {
        log.close();
    }

    @Test
    @DisplayName("no password, password hash, username or email address reaches the log, whatever the flow and however it ends")
    void nothingSecretOrPersonalIsLogged(CapturedOutput output) throws Exception {
        // Sign-up: accepted once, then refused three different ways.
        register(USERNAME, EMAIL, PASSWORD, PASSWORD).andExpect(redirectedUrl("/login?registered"));
        register(USERNAME, EMAIL, PASSWORD, PASSWORD).andExpect(status().isOk());
        register(OTHER_USERNAME, OTHER_EMAIL, PASSWORD, MISMATCHED_CONFIRMATION).andExpect(status().isOk());
        register(OTHER_USERNAME, OTHER_EMAIL, TOO_LONG_PASSWORD, TOO_LONG_PASSWORD).andExpect(status().isOk());

        // Sign-in: right, wrong, and with the password typed into the username box.
        mockMvc.perform(formLogin("/login").user(USERNAME).password(PASSWORD)).andExpect(authenticated());
        mockMvc.perform(formLogin("/login").user(USERNAME).password(WRONG_PASSWORD)).andExpect(unauthenticated());
        mockMvc.perform(formLogin("/login").user(PASSWORD).password(PASSWORD)).andExpect(unauthenticated());

        // The owner account, created the way startup creates it, and a second attempt
        // that fails with an unexpected exception and so with a stack trace.
        userService.createOwner(OWNER_USERNAME, OWNER_EMAIL, OWNER_PASSWORD);
        assertThatThrownBy(() -> userService.createOwner(OWNER_USERNAME, OWNER_EMAIL, OWNER_PASSWORD))
                .isInstanceOf(IllegalStateException.class);

        // A purchase, the buyer's history, the owner's report, and lookups by name.
        mockMvc.perform(post("/products/{id}/purchase", product.getId()).param("quantity", "1")
                        .with(buyer()).with(csrf()))
                .andExpect(redirectedUrl("/my-purchases"));
        mockMvc.perform(get("/my-purchases").with(buyer())).andExpect(status().isOk());
        mockMvc.perform(get("/owner/sales").with(user(OWNER_USERNAME).roles("OWNER")))
                .andExpect(content().string(containsString(USERNAME)));
        assertThat(userService.findByUsername(USERNAME).getEmail()).isEqualTo(EMAIL);
        assertThatThrownBy(() -> userService.findByUsername(UNKNOWN_USERNAME))
                .isInstanceOf(ResourceNotFoundException.class);

        String logged = log.everything();
        String console = output.getOut() + output.getErr();

        // The flows really were logged, so an empty log is not what makes this pass.
        assertThat(log.lines()).contains(
                "DEBUG AuthController - ENTER AuthController.register(form=<RegistrationForm>, binding=<BindingResult>)",
                "DEBUG UserService - ENTER UserService.registerBuyer(form=<RegistrationForm>)",
                "DEBUG UserRepository - ENTER UserRepository.save(entity=<User>)",
                "DEBUG UserRepository - EXIT UserRepository.save -> <User> [N ms]",
                "WARN AuthController - REJECTED POST /register [ERR-VALIDATION] 2 validation error(s) on [email, username]",
                "WARN AuthController - REJECTED POST /register [ERR-VALIDATION] 1 validation error(s) on [confirmPassword]",
                "WARN AuthController - REJECTED POST /register [ERR-VALIDATION] 1 validation error(s) on [password]",
                "DEBUG UserService - ENTER UserService.loadUserByUsername(username=<String>)",
                "DEBUG UserService - EXIT UserService.loadUserByUsername -> <UserDetails> [N ms]",
                "WARN UserService - EXCEPTION UserService.loadUserByUsername [ERR-NOTFOUND] "
                        + "UsernameNotFoundException: No account has that username",
                "DEBUG UserService - ENTER UserService.createOwner(username=<String>, email=<String>, rawPassword=<String>)",
                "ERROR UserService - EXCEPTION UserService.createOwner [ERR-UNEXPECTED] "
                        + "IllegalStateException: An owner account already exists; the shop has exactly one",
                "DEBUG PurchaseService - ENTER PurchaseService.findForBuyer(username=<String>)",
                "WARN UserService - EXCEPTION UserService.findByUsername [ERR-NOTFOUND] "
                        + "ResourceNotFoundException: No account has that username");
        assertThat(log.eventsWithStackTrace()).hasSize(1);
        assertThat(console)
                .contains("ENTER UserService.createOwner(username=<String>, email=<String>, rawPassword=<String>)")
                .contains("java.lang.IllegalStateException: An owner account already exists");

        for (String value : secretAndPersonalValues()) {
            assertThat(logged).as("log events").doesNotContain(value);
            assertThat(console).as("console output").doesNotContain(value);
        }
    }

    /** Every password and identity used above, plus the hashes the passwords were stored as. */
    private List<String> secretAndPersonalValues() {
        List<String> values = new ArrayList<>(List.of(
                PASSWORD, WRONG_PASSWORD, MISMATCHED_CONFIRMATION, TOO_LONG_PASSWORD, OWNER_PASSWORD,
                USERNAME, EMAIL, OTHER_USERNAME, OTHER_EMAIL, UNKNOWN_USERNAME, OWNER_USERNAME, OWNER_EMAIL));
        List<User> accounts = userRepository.findAll();
        assertThat(accounts).hasSize(2);
        accounts.forEach(account -> values.add(account.getPasswordHash()));
        return values;
    }

    private ResultActions register(String username, String email, String password, String confirm) throws Exception {
        return mockMvc.perform(post("/register").with(csrf())
                .param("username", username)
                .param("email", email)
                .param("password", password)
                .param("confirmPassword", confirm));
    }

    private static RequestPostProcessor buyer() {
        return user(USERNAME).roles("BUYER");
    }
}
