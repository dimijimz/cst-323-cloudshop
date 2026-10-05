package edu.gcu.cst323.cloudshop.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Lightweight liveness probe at /health.
 *
 * <p>Cloud platforms differ in what they probe by default, so this sits alongside
 * Spring Boot Actuator rather than replacing it:
 * <ul>
 *   <li>/health - liveness only. Deliberately does not touch the database, so a
 *       database blip does not make the platform recycle a healthy instance.</li>
 *   <li>/actuator/health - readiness. Its status reflects database connectivity,
 *       but it reports the status alone and never the component detail.</li>
 * </ul>
 */
@RestController
public class HealthController {

    @Value("${spring.application.name:cloudshop}")
    private String applicationName;

    /**
     * Reports that the application process is up and serving requests.
     *
     * @return the status, the application name and the current time, as JSON
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "application", applicationName,
                "timestamp", Instant.now().toString());
    }
}
