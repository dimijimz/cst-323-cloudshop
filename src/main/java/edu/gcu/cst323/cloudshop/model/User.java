package edu.gcu.cst323.cloudshop.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * An account that can sign in to the shop, either a buyer or the owner.
 *
 * <p>Maps the {@code users} table. Only the BCrypt hash of the password is ever
 * held here; the plain text is hashed by the service layer and then discarded.
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "username", nullable = false, length = 50, unique = true)
    private String username;

    @Column(name = "email", nullable = false, length = 120, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    // Pinned to VARCHAR. Hibernate 6 otherwise expects a native ENUM column on MySQL,
    // and schema validation would then reject the VARCHAR(10) the migration creates.
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "role", nullable = false, length = 10)
    private Role role;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** Required by JPA; application code uses the other constructor. */
    protected User() {
    }

    /**
     * Creates a new, not yet saved account.
     *
     * @param username     the sign-in name, unique across the shop
     * @param email        the contact address, unique across the shop
     * @param passwordHash the BCrypt hash of the password, never the plain text
     * @param role         whether this account is a buyer or the owner
     */
    public User(String username, String email, String passwordHash, Role role) {
        this.username = username;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
        // The column keeps microseconds; trimming here keeps memory and database equal.
        this.createdAt = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * Returns the database identifier.
     *
     * @return the generated id, or null before the account is saved
     */
    public Long getId() {
        return id;
    }

    /**
     * Returns the sign-in name.
     *
     * @return the username
     */
    public String getUsername() {
        return username;
    }

    /**
     * Returns the contact address.
     *
     * @return the email address
     */
    public String getEmail() {
        return email;
    }

    /**
     * Returns the stored password hash, for Spring Security to verify a sign-in against.
     *
     * @return the BCrypt hash
     */
    public String getPasswordHash() {
        return passwordHash;
    }

    /**
     * Returns what this account is allowed to do.
     *
     * @return {@link Role#BUYER} or {@link Role#OWNER}
     */
    public Role getRole() {
        return role;
    }

    /**
     * Returns when the account was created.
     *
     * @return the creation time, in the server's time zone
     */
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    /**
     * Describes the account for log output, leaving out the password hash.
     *
     * @return a short description naming the id, username and role
     */
    @Override
    public String toString() {
        return "User{id=" + id + ", username=" + username + ", role=" + role + "}";
    }
}
