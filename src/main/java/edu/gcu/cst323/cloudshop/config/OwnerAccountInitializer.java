package edu.gcu.cst323.cloudshop.config;

import edu.gcu.cst323.cloudshop.model.User;
import edu.gcu.cst323.cloudshop.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * Creates the shop's single owner account at startup, if there is not one yet.
 *
 * <p>The owner cannot register through the website, and no account is seeded by
 * the migrations, so this is the only way an owner comes to exist. The name and
 * password come from the OWNER_USERNAME and OWNER_PASSWORD environment variables,
 * which keeps the credential out of the repository and in the platform's settings.
 *
 * <p>It runs after the application context is ready, which means after Flyway has
 * built the schema. It only ever creates: once an owner exists, later changes to
 * the variables are ignored, so a redeploy can never reset the owner's password
 * or add a second owner.
 */
@Component
public class OwnerAccountInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OwnerAccountInitializer.class);

    private final UserService userService;
    private final String ownerUsername;
    private final String ownerPassword;
    private final String ownerEmail;

    /**
     * Creates the initializer with the configured owner credentials.
     *
     * @param userService   the service that creates the account and hashes the password
     * @param ownerUsername the value of OWNER_USERNAME, or empty if unset
     * @param ownerPassword the value of OWNER_PASSWORD, or empty if unset
     * @param ownerEmail    the value of OWNER_EMAIL, or its built-in default
     */
    public OwnerAccountInitializer(UserService userService,
                                   @Value("${app.owner.username:}") String ownerUsername,
                                   @Value("${app.owner.password:}") String ownerPassword,
                                   @Value("${app.owner.email:owner@cloudshop.local}") String ownerEmail) {
        this.userService = userService;
        this.ownerUsername = ownerUsername;
        this.ownerPassword = ownerPassword;
        this.ownerEmail = ownerEmail;
    }

    /**
     * Creates the owner account when none exists and both credentials are set.
     * Never stops the application from starting: a shop without an owner can still
     * serve buyers, so a problem here is logged rather than thrown.
     *
     * @param args the application arguments; not used
     */
    @Override
    public void run(ApplicationArguments args) {
        if (userService.ownerExists()) {
            log.info("Owner account already exists; OWNER_USERNAME and OWNER_PASSWORD were not applied");
            return;
        }
        if (ownerUsername.isBlank() || ownerPassword.isBlank()) {
            log.warn("No owner account exists and OWNER_USERNAME / OWNER_PASSWORD are not both set. "
                    + "The shop is running, but nobody can sign in as the owner. "
                    + "Set both variables and restart to create the owner account.");
            return;
        }
        try {
            User owner = userService.createOwner(ownerUsername, ownerEmail, ownerPassword);
            // The password is never logged, only the fact that the account now exists.
            log.info("Created the owner account '{}' from OWNER_USERNAME / OWNER_PASSWORD", owner.getUsername());
        } catch (IllegalStateException | DataIntegrityViolationException ex) {
            // Reached when the name or address already belongs to a buyer, or when a
            // second instance starting at the same moment created the owner first.
            log.error("Could not create the owner account '{}': {}", ownerUsername.trim(), ex.getMessage());
        }
    }
}
