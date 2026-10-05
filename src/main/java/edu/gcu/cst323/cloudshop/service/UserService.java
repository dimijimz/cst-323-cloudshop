package edu.gcu.cst323.cloudshop.service;

import edu.gcu.cst323.cloudshop.exception.ResourceNotFoundException;
import edu.gcu.cst323.cloudshop.form.RegistrationForm;
import edu.gcu.cst323.cloudshop.model.Role;
import edu.gcu.cst323.cloudshop.model.User;
import edu.gcu.cst323.cloudshop.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

/**
 * Business layer for accounts: buyer registration, the single owner account,
 * and the lookup Spring Security uses to sign someone in.
 *
 * <p>This is the application's {@link UserDetailsService}. Spring Security finds
 * it as the only bean of that type and pairs it with the BCrypt encoder from
 * {@code SecurityConfig}, so no authentication provider has to be wired by hand.
 *
 * <p>Passwords arrive here in plain text, are hashed once, and are never logged.
 */
@Service
@Transactional(readOnly = true)
public class UserService implements UserDetailsService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    /**
     * Creates the service.
     *
     * @param userRepository  data access for accounts
     * @param passwordEncoder the BCrypt encoder used to hash new passwords
     */
    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Loads an account for Spring Security during sign-in.
     *
     * @param username the name typed into the login form, in any letter case
     * @return the account's name, password hash and role as Spring Security expects them
     * @throws UsernameNotFoundException if no account has that name
     */
    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userRepository.findByUsernameIgnoreCase(username == null ? "" : username.trim())
                .orElseThrow(() -> new UsernameNotFoundException("No account named " + username));

        // The stored spelling becomes the principal name, so later lookups by
        // principal match the row exactly however the name was typed at login.
        return org.springframework.security.core.userdetails.User
                .withUsername(user.getUsername())
                .password(user.getPasswordHash())
                .roles(user.getRole().name())
                .build();
    }

    /**
     * Creates a buyer account from the sign-up form. The role is always
     * {@link Role#BUYER}; nothing on the form can change that.
     *
     * @param form the validated registration form
     * @return the saved buyer account
     */
    @Transactional
    public User registerBuyer(RegistrationForm form) {
        User saved = userRepository.save(new User(
                form.getUsername().trim(),
                normalizeEmail(form.getEmail()),
                passwordEncoder.encode(form.getPassword()),
                Role.BUYER));
        log.info("CREATE user: id={} username={} role={}", saved.getId(), saved.getUsername(), saved.getRole());
        return saved;
    }

    /**
     * Creates the shop's owner account. Called once at startup by
     * {@code OwnerAccountInitializer}; there is no web route that reaches it.
     *
     * @param username    the owner's sign-in name
     * @param email       the owner's contact address
     * @param rawPassword the owner's password in plain text, hashed before saving
     * @return the saved owner account
     * @throws IllegalStateException if an owner already exists, or the name or
     *                               address is already taken by another account
     */
    @Transactional
    public User createOwner(String username, String email, String rawPassword) {
        if (ownerExists()) {
            throw new IllegalStateException("An owner account already exists; the shop has exactly one");
        }
        if (usernameTaken(username)) {
            throw new IllegalStateException("The username '" + username.trim() + "' is already taken by another account");
        }
        if (emailTaken(email)) {
            throw new IllegalStateException("The email '" + normalizeEmail(email) + "' is already taken by another account");
        }
        User saved = userRepository.save(new User(
                username.trim(),
                normalizeEmail(email),
                passwordEncoder.encode(rawPassword),
                Role.OWNER));
        log.info("CREATE user: id={} username={} role={}", saved.getId(), saved.getUsername(), saved.getRole());
        return saved;
    }

    /**
     * Reports whether the shop already has its owner account.
     *
     * @return true if an account with the owner role exists
     */
    public boolean ownerExists() {
        return userRepository.existsByRole(Role.OWNER);
    }

    /**
     * Reports whether a sign-in name is already in use, so the form can show a
     * field error instead of the request failing on the unique constraint.
     *
     * @param username the name to check, in any letter case
     * @return true if an account already has that name
     */
    public boolean usernameTaken(String username) {
        return userRepository.existsByUsernameIgnoreCase(username.trim());
    }

    /**
     * Reports whether an email address is already in use.
     *
     * @param email the address to check, in any letter case
     * @return true if an account already has that address
     */
    public boolean emailTaken(String email) {
        return userRepository.existsByEmailIgnoreCase(normalizeEmail(email));
    }

    /**
     * Finds an account by its sign-in name.
     *
     * @param username the name to look up, in any letter case
     * @return the account
     * @throws ResourceNotFoundException if no account has that name
     */
    public User findByUsername(String username) {
        return userRepository.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new ResourceNotFoundException("No account named " + username));
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
