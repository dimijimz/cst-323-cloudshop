package edu.gcu.cst323.cloudshop.controller;

import edu.gcu.cst323.cloudshop.form.RegistrationForm;
import edu.gcu.cst323.cloudshop.service.UserService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * Sign-in and buyer sign-up pages.
 *
 * <p>This controller only renders the login form. Checking the submitted
 * username and password is done by Spring Security, which handles the form's
 * POST to /login itself, before any controller is reached.
 */
@Controller
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final UserService userService;

    /**
     * Creates the controller.
     *
     * @param userService creates buyer accounts and checks for duplicates
     */
    public AuthController(UserService userService) {
        this.userService = userService;
    }

    /**
     * Renders the sign-in form. The template reads the {@code error},
     * {@code logout} and {@code registered} query parameters to show the
     * matching banner.
     *
     * @return the login view
     */
    @GetMapping("/login")
    public String login() {
        return "auth/login";
    }

    /**
     * Renders a blank buyer sign-up form.
     *
     * @param model receives the empty form object
     * @return the registration view
     */
    @GetMapping("/register")
    public String registerForm(Model model) {
        model.addAttribute("registrationForm", new RegistrationForm());
        return "auth/register";
    }

    /**
     * Creates a buyer account from the sign-up form, then sends the new buyer to
     * sign in. Redisplays the form with field errors if anything is invalid, the
     * passwords differ, or the username or email is already taken.
     *
     * @param form    the submitted form
     * @param binding the validation result for the form
     * @return a redirect to the login page on success, otherwise the registration view
     */
    @PostMapping("/register")
    public String register(@Valid @ModelAttribute("registrationForm") RegistrationForm form,
                           BindingResult binding) {
        rejectMismatchedPasswords(form, binding);
        rejectDuplicates(form, binding);

        if (binding.hasErrors()) {
            log.warn("POST /register - rejected with {} validation error(s)", binding.getErrorCount());
            return "auth/register";
        }

        try {
            userService.registerBuyer(form);
        } catch (DataIntegrityViolationException ex) {
            // Two sign-ups for the same name at the same instant: both passed the
            // check above, and the unique constraint stopped the second one.
            log.warn("POST /register - lost a race on the unique constraints for username={}", form.getUsername());
            binding.rejectValue("username", "username.taken", "That username or email was just taken");
            return "auth/register";
        }
        return "redirect:/login?registered";
    }

    private void rejectMismatchedPasswords(RegistrationForm form, BindingResult binding) {
        if (binding.hasFieldErrors("password") || binding.hasFieldErrors("confirmPassword")) {
            return;
        }
        if (!form.getPassword().equals(form.getConfirmPassword())) {
            binding.rejectValue("confirmPassword", "password.mismatch", "Passwords do not match");
        }
    }

    private void rejectDuplicates(RegistrationForm form, BindingResult binding) {
        if (!binding.hasFieldErrors("username") && userService.usernameTaken(form.getUsername())) {
            binding.rejectValue("username", "username.taken", "That username is already taken");
        }
        if (!binding.hasFieldErrors("email") && userService.emailTaken(form.getEmail())) {
            binding.rejectValue("email", "email.taken", "That email is already registered");
        }
    }
}
