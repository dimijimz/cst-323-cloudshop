package edu.gcu.cst323.cloudshop.form;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Backs the buyer sign-up form at /register.
 *
 * <p>There is deliberately no role field. Everyone who registers becomes a buyer,
 * so nothing a visitor submits can ask for an owner account.
 */
public class RegistrationForm {

    @NotBlank(message = "Username is required")
    @Size(min = 3, max = 50, message = "Username must be between 3 and 50 characters")
    @Pattern(regexp = "^[A-Za-z0-9._-]*$",
            message = "Username may only contain letters, digits, dots, hyphens and underscores")
    private String username;

    @NotBlank(message = "Email is required")
    @Email(message = "Enter a valid email address")
    @Size(max = 120, message = "Email must be 120 characters or fewer")
    private String email;

    // BCrypt only reads the first 72 bytes of a password, so anything longer would be
    // silently truncated. Capping the length keeps what was typed and what is checked equal.
    @NotBlank(message = "Password is required")
    @Size(min = 8, max = 72, message = "Password must be between 8 and 72 characters")
    private String password;

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
