package edu.gcu.cst323.cloudshop.form;

import edu.gcu.cst323.cloudshop.model.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Backs the buyer sign-up form at /register.
 *
 * <p>There is deliberately no role field. Everyone who registers becomes a buyer,
 * so nothing a visitor submits can ask for an owner account.
 *
 * @author Dimitri Jimenez
 */
public class RegistrationForm {

    /** The shortest sign-in name accepted. The longest is what the column holds. */
    private static final int USERNAME_MIN_LENGTH = 3;

    /** The shortest password accepted. */
    private static final int PASSWORD_MIN_LENGTH = 8;

    /**
     * The longest password accepted. BCrypt only reads the first 72 bytes of a
     * password, so anything longer would be silently truncated; capping the length
     * keeps what was typed and what is checked the same.
     */
    private static final int PASSWORD_MAX_LENGTH = 72;

    /** The requested sign-in name: letters, digits, dots, hyphens and underscores only. */
    @NotBlank(message = "Username is required")
    @Size(min = USERNAME_MIN_LENGTH, max = User.USERNAME_MAX_LENGTH,
            message = "Username must be between {min} and {max} characters")
    @Pattern(regexp = "^[A-Za-z0-9._-]*$",
            message = "Username may only contain letters, digits, dots, hyphens and underscores")
    private String username;

    /** The contact address, which no other account may already use. */
    @NotBlank(message = "Email is required")
    @Email(message = "Enter a valid email address")
    @Size(max = User.EMAIL_MAX_LENGTH, message = "Email must be {max} characters or fewer")
    private String email;

    /** The chosen password, in plain text until the service hashes it. */
    @NotBlank(message = "Password is required")
    @Size(min = PASSWORD_MIN_LENGTH, max = PASSWORD_MAX_LENGTH,
            message = "Password must be between {min} and {max} characters")
    private String password;

    /** The password typed a second time, which must match the first. */
    @NotBlank(message = "Please confirm your password")
    private String confirmPassword;

    /** Creates an empty form for the blank sign-up page. */
    public RegistrationForm() {
    }

    /**
     * Returns the requested sign-in name.
     *
     * @return the username as typed
     */
    public String getUsername() {
        return username;
    }

    /**
     * Sets the requested sign-in name.
     *
     * @param username the username as typed
     */
    public void setUsername(String username) {
        this.username = username;
    }

    /**
     * Returns the contact address.
     *
     * @return the email as typed
     */
    public String getEmail() {
        return email;
    }

    /**
     * Sets the contact address.
     *
     * @param email the email as typed
     */
    public void setEmail(String email) {
        this.email = email;
    }

    /**
     * Returns the chosen password in plain text. It is hashed before it is stored.
     *
     * @return the password as typed
     */
    public String getPassword() {
        return password;
    }

    /**
     * Sets the chosen password.
     *
     * @param password the password as typed
     */
    public void setPassword(String password) {
        this.password = password;
    }

    /**
     * Returns the repeated password, which must match {@link #getPassword()}.
     *
     * @return the confirmation as typed
     */
    public String getConfirmPassword() {
        return confirmPassword;
    }

    /**
     * Sets the repeated password.
     *
     * @param confirmPassword the confirmation as typed
     */
    public void setConfirmPassword(String confirmPassword) {
        this.confirmPassword = confirmPassword;
    }
}
