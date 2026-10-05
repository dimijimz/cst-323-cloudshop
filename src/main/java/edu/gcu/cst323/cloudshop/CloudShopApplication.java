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
        // Logged without credentials so the startup line is safe to keep in cloud log streams.
        log.info("CloudShop started on port {} against datasource {}",
                env.getProperty("server.port"),
                env.getProperty("spring.datasource.url"));
    }
}
