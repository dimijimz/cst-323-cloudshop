package edu.gcu.cst323.cloudshop;

import edu.gcu.cst323.cloudshop.model.Role;
import edu.gcu.cst323.cloudshop.model.User;
import edu.gcu.cst323.cloudshop.repository.PurchaseRepository;
import edu.gcu.cst323.cloudshop.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Buyer sign-up through the real /register form, and signing in afterwards
 * through Spring Security's real login processing.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"h2", "test"})
class RegistrationTest {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PurchaseRepository purchaseRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        purchaseRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("the registration page renders an empty form")
    void registrationPageRenders() throws Exception {
        mockMvc.perform(get("/register"))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/register"))
                .andExpect(content().string(containsString("Create a buyer account")));
    }

    @Test
    @DisplayName("a valid sign-up creates a buyer whose password is stored only as a BCrypt hash")
    void validRegistrationCreatesABuyer() throws Exception {
        register("alice", "Alice@Example.com", PASSWORD, PASSWORD)
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?registered"));

        User alice = userRepository.findByUsernameIgnoreCase("alice").orElseThrow();
        assertThat(alice.getRole()).isEqualTo(Role.BUYER);
        assertThat(alice.getEmail()).isEqualTo("alice@example.com");
        assertThat(alice.getCreatedAt()).isNotNull();

        assertThat(alice.getPasswordHash())
                .isNotEqualTo(PASSWORD)
                .doesNotContain(PASSWORD)
                .startsWith("$2")
                .hasSize(60);
        assertThat(passwordEncoder.matches(PASSWORD, alice.getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches("some-other-password", alice.getPasswordHash())).isFalse();
    }

    @Test
    @DisplayName("a newly registered buyer can sign in and lands on the catalog as a BUYER")
    void registeredBuyerCanSignIn() throws Exception {
        register("alice", "alice@example.com", PASSWORD, PASSWORD);

        mockMvc.perform(formLogin("/login").user("alice").password(PASSWORD))
                .andExpect(authenticated().withUsername("alice").withRoles("BUYER"))
                .andExpect(redirectedUrl("/products"));

        // The username is matched without regard to case, and resolves to the stored spelling.
        mockMvc.perform(formLogin("/login").user("ALICE").password(PASSWORD))
                .andExpect(authenticated().withUsername("alice").withRoles("BUYER"));
    }

    @Test
    @DisplayName("a wrong password or an unknown username does not sign anyone in")
    void badCredentialsAreRejected() throws Exception {
        register("alice", "alice@example.com", PASSWORD, PASSWORD);

        mockMvc.perform(formLogin("/login").user("alice").password("not-the-password"))
                .andExpect(unauthenticated())
                .andExpect(redirectedUrl("/login?error"));
        mockMvc.perform(formLogin("/login").user("nobody").password(PASSWORD))
                .andExpect(unauthenticated())
                .andExpect(redirectedUrl("/login?error"));
    }

    @Test
    @DisplayName("nothing submitted to /register can create an owner account")
    void registrationCannotCreateAnOwner() throws Exception {
        mockMvc.perform(post("/register").with(csrf())
                        .param("username", "mallory")
                        .param("email", "mallory@example.com")
                        .param("password", PASSWORD)
                        .param("confirmPassword", PASSWORD)
                        .param("role", "OWNER"))
                .andExpect(status().is3xxRedirection());

        assertThat(userRepository.findByUsernameIgnoreCase("mallory").orElseThrow().getRole()).isEqualTo(Role.BUYER);
        assertThat(userRepository.existsByRole(Role.OWNER)).isFalse();

        mockMvc.perform(formLogin("/login").user("mallory").password(PASSWORD))
                .andExpect(authenticated().withRoles("BUYER"));
    }

    @Test
    @DisplayName("a username already in use is rejected, whatever its letter case")
    void duplicateUsernameIsRejected() throws Exception {
        register("alice", "alice@example.com", PASSWORD, PASSWORD);

        register("ALICE", "another@example.com", PASSWORD, PASSWORD)
                .andExpect(status().isOk())
                .andExpect(view().name("auth/register"))
                .andExpect(model().attributeHasFieldErrors("registrationForm", "username"))
                .andExpect(content().string(containsString("That username is already taken")));

        assertThat(userRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("an email already registered is rejected, whatever its letter case")
    void duplicateEmailIsRejected() throws Exception {
        register("alice", "alice@example.com", PASSWORD, PASSWORD);

        register("alice2", "ALICE@example.com", PASSWORD, PASSWORD)
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("registrationForm", "email"))
                .andExpect(content().string(containsString("That email is already registered")));

        assertThat(userRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("passwords that do not match are rejected and never echoed back")
    void mismatchedPasswordsAreRejected() throws Exception {
        register("alice", "alice@example.com", PASSWORD, "something-else-entirely")
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("registrationForm", "confirmPassword"))
                .andExpect(content().string(containsString("Passwords do not match")))
                .andExpect(content().string(not(containsString(PASSWORD))))
                .andExpect(content().string(not(containsString("something-else-entirely"))));

        assertThat(userRepository.count()).isZero();
    }

    @Test
    @DisplayName("a short password, a malformed email and an invalid username are each rejected")
    void invalidFieldsAreRejected() throws Exception {
        register("alice", "alice@example.com", "short", "short")
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("registrationForm", "password"))
                .andExpect(content().string(containsString("Password must be between 8 and 72 characters")));

        register("alice", "not-an-email", PASSWORD, PASSWORD)
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("registrationForm", "email"));

        register("al ice!", "alice@example.com", PASSWORD, PASSWORD)
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("registrationForm", "username"));

        register("", "", "", "")
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("registrationForm",
                        "username", "email", "password", "confirmPassword"))
                .andExpect(content().string(containsString("Username is required")));

        assertThat(userRepository.count()).isZero();
    }

    @Test
    @DisplayName("a sign-up without a CSRF token is refused")
    void registrationRequiresCsrfToken() throws Exception {
        mockMvc.perform(post("/register")
                        .param("username", "alice")
                        .param("email", "alice@example.com")
                        .param("password", PASSWORD)
                        .param("confirmPassword", PASSWORD))
                .andExpect(status().isForbidden());

        assertThat(userRepository.count()).isZero();
    }

    private ResultActions register(String username, String email, String password, String confirm) throws Exception {
        return mockMvc.perform(post("/register").with(csrf())
                .param("username", username)
                .param("email", email)
                .param("password", password)
                .param("confirmPassword", confirm));
    }
}
