package edu.gcu.cst323.cloudshop.logging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the logging configuration itself: where the log goes, what a line looks
 * like, and where the level comes from. These are settings a platform's log
 * collector and the deployment notes both depend on, so a change to any of them
 * should be a deliberate one.
 */
class LoggingConfigurationTest {

    private static final String PATTERN = "%d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX} %-5level [%thread] %logger{36} - %msg%n";

    @Test
    @DisplayName("the only appender is the console, writing to stdout in the agreed pattern")
    void consoleIsTheOnlyAppender() throws Exception {
        Document config = logbackConfiguration();

        NodeList appenders = config.getElementsByTagName("appender");
        assertThat(appenders.getLength()).isEqualTo(1);
        Element console = (Element) appenders.item(0);
        assertThat(console.getAttribute("class")).isEqualTo("ch.qos.logback.core.ConsoleAppender");
        // No <target>, so the appender keeps its default, System.out.
        assertThat(console.getElementsByTagName("target").getLength()).isZero();

        NodeList patterns = console.getElementsByTagName("pattern");
        assertThat(patterns.getLength()).isEqualTo(1);
        assertThat(patterns.item(0).getTextContent().trim()).isEqualTo(PATTERN);

        NodeList references = config.getElementsByTagName("appender-ref");
        assertThat(references.getLength()).isEqualTo(1);
        assertThat(((Element) references.item(0)).getAttribute("ref")).isEqualTo(console.getAttribute("name"));
    }

    @Test
    @DisplayName("the application's log level comes from APP_LOG_LEVEL and defaults to INFO")
    void levelComesFromTheEnvironment() throws Exception {
        Properties properties = applicationProperties();

        assertThat(properties.getProperty("logging.level.edu.gcu.cst323.cloudshop")).isEqualTo("${APP_LOG_LEVEL:INFO}");
        // The Logback file must not pin a level of its own over the top of it.
        assertThat(logbackConfiguration().getElementsByTagName("logger").getLength()).isZero();
    }

    @Test
    @DisplayName("nothing configures a log file")
    void noLogFileIsConfigured() throws Exception {
        assertThat(applicationProperties().stringPropertyNames())
                .noneMatch(name -> name.startsWith("logging.file") || name.equals("logging.config"));
    }

    @Test
    @DisplayName("the start-up banner is off, so every line on stdout is a log line in the pattern")
    void bannerIsOff() throws Exception {
        assertThat(applicationProperties().getProperty("spring.main.banner-mode")).isEqualTo("off");
    }

    private static Document logbackConfiguration() throws Exception {
        try (InputStream in = LoggingConfigurationTest.class.getResourceAsStream("/logback-spring.xml")) {
            assertThat(in).as("logback-spring.xml on the classpath").isNotNull();
            return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(in);
        }
    }

    private static Properties applicationProperties() throws Exception {
        Properties properties = new Properties();
        try (InputStream in = LoggingConfigurationTest.class.getResourceAsStream("/application.properties")) {
            assertThat(in).as("application.properties on the classpath").isNotNull();
            properties.load(in);
        }
        return properties;
    }
}
