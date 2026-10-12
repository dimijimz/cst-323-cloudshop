package edu.gcu.cst323.cloudshop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.env.Environment;

/**
 * Entry point for CloudShop, the CST-323 Milestone 3 application.
 *
 * <p>CloudShop is a small online shop with two roles. Buyers register, browse the
 * catalog and purchase products; the single shop owner manages inventory and
 * reviews sales. Every environment-specific value (database host, credentials,
 * HTTP port, owner account) is supplied externally rather than baked into the
 * jar, so the same build runs unchanged on each cloud platform.
 */
@SpringBootApplication
public class CloudShopApplication {

    private static final Logger log = LoggerFactory.getLogger(CloudShopApplication.class);

    /**
     * Starts the embedded web server and the Spring application context.
     *
     * @param args command-line arguments, passed through to Spring Boot
     */
    public static void main(String[] args) {
        Environment env = SpringApplication.run(CloudShopApplication.class, args).getEnvironment();
        log.info("CloudShop started on port {} against datasource {}",
                env.getProperty("server.port"),
                describeDatasource(env.getProperty("spring.datasource.url")));
    }

    /**
     * Reduces a JDBC URL to the part that says where the database is, for the
     * start-up line.
     *
     * <p>This application's own URL carries no credentials: the user and password
     * are separate properties. A platform can still hand it one that does. Heroku's
     * Java buildpack, unless told not to, supplies a URL ending in
     * {@code ?user=...&password=...}, and it takes precedence over the one in
     * {@code application.properties}. So nothing after the address is logged, and
     * a {@code user:password@} before the host is dropped as well.
     *
     * @param jdbcUrl the configured datasource URL; may be null
     * @return the URL without its query string, its {@code ;} settings or any
     *         credentials before the host
     */
    static String describeDatasource(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            return "(not configured)";
        }
        String address = jdbcUrl;

        // Credentials before the host come first, because a password may itself
        // contain a ';'. They end at the last '@' of the authority, and the
        // authority ends at the first '/', '?' or '#'.
        int authorityStart = address.indexOf("//");
        if (authorityStart >= 0) {
            int from = authorityStart + 2;
            int authorityEnd = address.length();
            for (char end : new char[] {'/', '?', '#'}) {
                int at = address.indexOf(end, from);
                if (at >= 0 && at < authorityEnd) {
                    authorityEnd = at;
                }
            }
            int lastAt = address.lastIndexOf('@', authorityEnd - 1);
            if (lastAt >= from) {
                address = address.substring(0, from) + address.substring(lastAt + 1);
            }
        }

        // Then the options: MySQL and PostgreSQL put them after '?', H2 and SQL
        // Server after ';'.
        for (char optionsStart : new char[] {'?', ';', '#'}) {
            int at = address.indexOf(optionsStart);
            if (at >= 0) {
                address = address.substring(0, at);
            }
        }

        // A URL too malformed to take apart is not worth guessing at.
        return address.contains("@") ? "(address withheld)" : address;
    }
}
