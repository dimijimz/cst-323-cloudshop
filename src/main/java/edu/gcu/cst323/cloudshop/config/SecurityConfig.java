package edu.gcu.cst323.cloudshop.config;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;

/**
 * Spring Security setup: who may reach which URL, form login, and BCrypt hashing.
 *
 * <p>The URL rules are the access-control policy for the whole shop:
 * <ul>
 *   <li>Public - the home page, the catalog, sign-in, sign-up and the health probes,
 *       to read with GET or HEAD.</li>
 *   <li>BUYER only - making a purchase and viewing one's own purchase history.</li>
 *   <li>OWNER only - everything under /owner: inventory and the sales report.</li>
 *   <li>Anything not listed - signed-in users only, so a route added later is
 *       closed by default rather than open by accident.</li>
 * </ul>
 *
 * <p>Accounts are loaded by {@code UserService}, which Spring Security picks up
 * automatically as the application's only UserDetailsService. CSRF protection is
 * left on; Thymeleaf adds the token to every form it renders.
 *
 * @author Dimitri Jimenez
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** The authority Spring Security gives an account whose role is OWNER. */
    private static final String OWNER_AUTHORITY = "ROLE_OWNER";

    /**
     * The URL patterns anyone may read without signing in.
     *
     * <p>They are open to HEAD as well as GET. A HEAD request asks for the same
     * response without its body, and it is what an uptime monitor such as Uptime
     * Robot sends, so it has to reach the page rather than be redirected to the
     * login form. Neither method can change anything.
     */
    private static final String[] PUBLIC_PAGES = {
            "/", "/products", "/products/*",
            "/login", "/register",
            "/css/**", "/favicon.ico", "/error",
            "/health", "/actuator/health", "/actuator/health/**", "/actuator/info"
    };

    /**
     * Defines the URL rules, the login form and logout.
     *
     * @param http the builder Spring Security supplies
     * @return the filter chain applied to every request
     * @throws Exception if the configuration cannot be built
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        // Error pages are rendered on an internal dispatch that must not
                        // itself be bounced to the login form.
                        .dispatcherTypeMatchers(DispatcherType.FORWARD, DispatcherType.ERROR).permitAll()

                        .requestMatchers("/owner/**").hasRole("OWNER")
                        .requestMatchers(HttpMethod.POST, "/products/*/purchase").hasRole("BUYER")
                        .requestMatchers("/my-purchases").hasRole("BUYER")

                        .requestMatchers(HttpMethod.GET, PUBLIC_PAGES).permitAll()
                        .requestMatchers(HttpMethod.HEAD, PUBLIC_PAGES).permitAll()
                        .requestMatchers(HttpMethod.POST, "/register").permitAll()

                        .anyRequest().authenticated())
                .formLogin(form -> form
                        .loginPage("/login")
                        .successHandler(roleAwareSuccessHandler())
                        .permitAll())
                .logout(logout -> logout
                        .logoutSuccessUrl("/login?logout")
                        .permitAll());
        return http.build();
    }

    /**
     * Supplies the password hashing algorithm for registration and sign-in.
     *
     * @return a BCrypt encoder, which salts each hash and is deliberately slow to compute
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Decides where someone lands after signing in. If they were sent to the login
     * page from a protected URL they go back to it; otherwise the owner starts at
     * the inventory and a buyer starts at the catalog.
     *
     * @return the handler Spring Security calls after a successful sign-in
     */
    private AuthenticationSuccessHandler roleAwareSuccessHandler() {
        return new SavedRequestAwareAuthenticationSuccessHandler() {
            /**
             * Chooses the landing page by role, for a sign-in with no saved request to return to.
             *
             * @param request        the sign-in request
             * @param response       the response the redirect is written to
             * @param authentication the account that has just signed in
             * @return {@code /owner/products} for the owner, {@code /products} for a buyer
             */
            @Override
            protected String determineTargetUrl(HttpServletRequest request,
                                                HttpServletResponse response,
                                                Authentication authentication) {
                boolean owner = authentication.getAuthorities().stream()
                        .anyMatch(authority -> OWNER_AUTHORITY.equals(authority.getAuthority()));
                return owner ? "/owner/products" : "/products";
            }
        };
    }
}
