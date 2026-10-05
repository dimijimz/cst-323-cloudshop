package edu.gcu.cst323.cloudshop.repository;

import edu.gcu.cst323.cloudshop.model.Role;
import edu.gcu.cst323.cloudshop.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Data access for {@link User} accounts.
 *
 * <p>The lookups ignore case because MySQL's default collation already treats
 * {@code Alice} and {@code alice} as the same value under the unique constraints.
 * Matching that here keeps the application's own checks in step with the database.
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * Finds an account by its sign-in name.
     *
     * @param username the name to look up, in any letter case
     * @return the account, or empty if no account has that name
     */
    Optional<User> findByUsernameIgnoreCase(String username);

    /**
     * Reports whether a sign-in name is already in use.
     *
     * @param username the name to check, in any letter case
     * @return true if an account already has that name
     */
    boolean existsByUsernameIgnoreCase(String username);

    /**
     * Reports whether an email address is already in use.
     *
     * @param email the address to check, in any letter case
     * @return true if an account already has that address
     */
    boolean existsByEmailIgnoreCase(String email);

    /**
     * Reports whether any account holds the given role. Used to keep the shop to
     * exactly one owner.
     *
     * @param role the role to look for
     * @return true if at least one account has that role
     */
    boolean existsByRole(Role role);
}
