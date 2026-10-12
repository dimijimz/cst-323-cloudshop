package edu.gcu.cst323.cloudshop;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The start-up line names the database the application is using. It must do
 * that without ever repeating a credential, whatever form of JDBC URL the
 * platform supplied.
 *
 * <p>This is not hypothetical. On Heroku the Java buildpack supplied a URL ending
 * in {@code ?password=...&user=...}, which overrode the application's own, and an
 * earlier version of the line logged it whole.
 */
class StartupLineTest {

    private static final String SECRET = "s3cret-db-password-canary";

    @Test
    @DisplayName("the application's own URL is reduced to where the database is")
    void ownUrlKeepsOnlyTheAddress() {
        assertThat(CloudShopApplication.describeDatasource(
                "jdbc:mysql://db.example.com:3306/cloudshop?sslMode=REQUIRED&allowPublicKeyRetrieval=true&serverTimezone=UTC"))
                .isEqualTo("jdbc:mysql://db.example.com:3306/cloudshop");
    }

    @Test
    @DisplayName("credentials in the query string, as Heroku's buildpack writes them, are not logged")
    void queryStringCredentialsAreDropped() {
        String described = CloudShopApplication.describeDatasource(
                "jdbc:mysql://db.example.com:3306/schema1?password=" + SECRET + "&user=dbuser-canary");

        assertThat(described).isEqualTo("jdbc:mysql://db.example.com:3306/schema1");
        assertThat(described).doesNotContain(SECRET, "dbuser-canary", "password", "user=");
    }

    @Test
    @DisplayName("credentials written before the host are not logged")
    void userInfoCredentialsAreDropped() {
        String described = CloudShopApplication.describeDatasource(
                "jdbc:mysql://dbuser-canary:" + SECRET + "@db.example.com:3306/schema1?sslMode=REQUIRED");

        assertThat(described).isEqualTo("jdbc:mysql://db.example.com:3306/schema1");
        assertThat(described).doesNotContain(SECRET, "dbuser-canary");
    }

    @Test
    @DisplayName("a password before the host is dropped whole even when it contains a semicolon or an at sign")
    void awkwardPasswordsBeforeTheHostAreDropped() {
        for (String password : new String[] {"s3cret;canary", "s3cret@canary", "s3cret;can@ry;"}) {
            String described = CloudShopApplication.describeDatasource(
                    "jdbc:mysql://dbuser-canary:" + password + "@db.example.com:3306/schema1?sslMode=REQUIRED");

            assertThat(described).as(password).isEqualTo("jdbc:mysql://db.example.com:3306/schema1");
        }
    }

    @Test
    @DisplayName("a URL that still holds an at sign after all that is withheld rather than guessed at")
    void unparseableUrlIsWithheld() {
        String described = CloudShopApplication.describeDatasource(
                "jdbc:mysql://dbuser-canary:s3cret/canary@db.example.com:3306/schema1");

        assertThat(described).isEqualTo("(address withheld)");
    }

    @Test
    @DisplayName("an H2 URL loses its settings, which is where H2 carries a password")
    void h2SettingsAreDropped() {
        assertThat(CloudShopApplication.describeDatasource(
                "jdbc:h2:mem:cloudshop;MODE=MySQL;USER=sa;PASSWORD=" + SECRET + ";DB_CLOSE_DELAY=-1"))
                .isEqualTo("jdbc:h2:mem:cloudshop")
                .doesNotContain(SECRET);
    }

    @Test
    @DisplayName("a missing URL is said to be missing rather than printed as null")
    void missingUrl() {
        assertThat(CloudShopApplication.describeDatasource(null)).isEqualTo("(not configured)");
        assertThat(CloudShopApplication.describeDatasource("  ")).isEqualTo("(not configured)");
    }
}
