package edu.gcu.cst323.cloudshop;

import edu.gcu.cst323.cloudshop.config.OwnerAccountInitializer;
import edu.gcu.cst323.cloudshop.model.Role;
import edu.gcu.cst323.cloudshop.model.User;
import edu.gcu.cst323.cloudshop.repository.UserRepository;
import edu.gcu.cst323.cloudshop.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

/**
 * Starts the application with owner credentials configured, the way a
 * deployment sets OWNER_USERNAME and OWNER_PASSWORD, and checks the owner
 * account that results.
 *
 * <p>Runs against its own in-memory database so the owner it creates cannot
 * leak into the other test classes, which assume no owner exists.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:ownerinit;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "app.owner.username=shopowner",
        "app.owner.password=" + OwnerAccountInitializerTest.OWNER_PASSWORD,
        "app.owner.email=ShopOwner@Example.com"
})
@AutoConfigureMockMvc
@ActiveProfiles({"h2", "test"})
class OwnerAccountInitializerTest {

    static final String OWNER_PASSWORD = "test-only-owner-password";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    @DisplayName("startup creates the owner account from the configured credentials, with a hashed password")
    void ownerIsCreatedAtStartup() {
        User owner = userRepository.findByUsernameIgnoreCase("shopowner").orElseThrow();

        assertThat(owner.getRole()).isEqualTo(Role.OWNER);
        assertThat(owner.getEmail()).isEqualTo("shopowner@example.com");
        assertThat(owner.getPasswordHash()).isNotEqualTo(OWNER_PASSWORD).startsWith("$2");
        assertThat(passwordEncoder.matches(OWNER_PASSWORD, owner.getPasswordHash())).isTrue();
    }

    @Test
    @DisplayName("the owner can sign in and lands on the inventory as an OWNER")
    void ownerCanSignIn() throws Exception {
        mockMvc.perform(formLogin("/login").user("shopowner").password(OWNER_PASSWORD))
                .andExpect(authenticated().withUsername("shopowner").withRoles("OWNER"))
                .andExpect(redirectedUrl("/owner/products"));
    }

    @Test
    @DisplayName("a restart with the same or with different credentials never creates a second owner")
    void thereIsOnlyEverOneOwner() {
        String originalHash = userRepository.findByUsernameIgnoreCase("shopowner").orElseThrow().getPasswordHash();

        // Same credentials again: what every ordinary restart does.
        new OwnerAccountInitializer(userService, "shopowner", OWNER_PASSWORD, "shopowner@example.com").run(null);
        // Different credentials: someone changed the variables after the owner existed.
        new OwnerAccountInitializer(userService, "someone-else", "a-different-password", "else@example.com").run(null);

        assertThat(owners()).extracting(User::getUsername).containsExactly("shopowner");
        assertThat(userRepository.findByUsernameIgnoreCase("someone-else")).isEmpty();
        // And the existing owner's password was not reset by either run.
        assertThat(userRepository.findByUsernameIgnoreCase("shopowner").orElseThrow().getPasswordHash())
                .isEqualTo(originalHash);
    }

    @Test
    @DisplayName("the service itself refuses to create a second owner")
    void serviceRefusesASecondOwner() {
        assertThatThrownBy(() -> userService.createOwner("second-owner", "second@example.com", "another-password"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already exists");

        assertThat(owners()).hasSize(1);
    }

    @Test
    @DisplayName("with either credential missing, no owner is created and startup still completes")
    void missingCredentialsCreateNoOwner() {
        UserService noOwnerYet = mock(UserService.class);
        when(noOwnerYet.ownerExists()).thenReturn(false);

        new OwnerAccountInitializer(noOwnerYet, "", "", "owner@example.com").run(null);
        new OwnerAccountInitializer(noOwnerYet, "shopowner", "  ", "owner@example.com").run(null);
        new OwnerAccountInitializer(noOwnerYet, "", OWNER_PASSWORD, "owner@example.com").run(null);

        verify(noOwnerYet, never()).createOwner(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("a username already held by a buyer is logged, not thrown, so startup still completes")
    void conflictingUsernameDoesNotStopStartup() {
        UserService conflicted = mock(UserService.class);
        when(conflicted.ownerExists()).thenReturn(false);
        when(conflicted.createOwner(anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("The username 'alice' is already taken by another account"));

        // Must return normally.
        new OwnerAccountInitializer(conflicted, "alice", OWNER_PASSWORD, "owner@example.com").run(null);

        verify(conflicted).createOwner("alice", "owner@example.com", OWNER_PASSWORD);
    }

    private List<User> owners() {
        return userRepository.findAll().stream().filter(user -> user.getRole() == Role.OWNER).toList();
    }
}
