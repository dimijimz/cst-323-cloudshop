package edu.gcu.cst323.cloudshop;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Checks what the Flyway migrations actually build, using plain SQL so that no
 * application code stands between the assertions and the database.
 *
 * <p>Starting this context at all is the first check: Hibernate runs in
 * {@code validate} mode, so the application would refuse to start if the entity
 * mappings disagreed with the schema the migrations create.
 *
 * <p>Runs against its own in-memory database, so it sees the schema exactly as
 * the migrations leave it, untouched by the other test classes. Each test runs
 * in a transaction that is rolled back afterwards.
 *
 * <p>One caveat belongs here: this is H2 in MySQL mode, not MySQL. It proves the
 * migrations run and the constraints are declared; it cannot prove MySQL itself
 * accepts the same file.
 */
@SpringBootTest(properties =
        "spring.datasource.url=jdbc:h2:mem:migrationcheck;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles({"h2", "test"})
@Transactional
class SchemaMigrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("Flyway applied V1 and V2, in order, successfully")
    void bothMigrationsWereApplied() {
        // "version is not null" skips the marker row Flyway writes on H2 when it creates
        // the schema itself; only real, versioned migrations are of interest here.
        List<String> versions = jdbc.queryForList(
                "select version from flyway_schema_history "
                        + "where success = true and version is not null order by installed_rank", String.class);

        assertThat(versions).containsExactly("1", "2");
    }

    @Test
    @DisplayName("the seed creates eight products and no accounts or purchases")
    void seedCreatesProductsAndNoAccounts() {
        assertThat(count("products")).isEqualTo(8);
        assertThat(count("users")).isZero();
        assertThat(count("purchases")).isZero();

        assertThat(jdbc.queryForObject("select count(*) from products where active = true", Integer.class)).isEqualTo(8);
        // The two rows the demo relies on: a last unit to race for, and an out-of-stock product.
        assertThat(jdbc.queryForObject("select count(*) from products where stock = 1", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from products where stock = 0", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("CHECK: a product's price and stock cannot be negative")
    void negativePriceAndStockAreRejected() {
        assertRejected("update products set stock = -1 where name = 'Wireless Mouse'");
        assertRejected("update products set price = -0.01 where name = 'Wireless Mouse'");
        assertRejected("insert into products (name, description, price, stock, active) values ('Bad', 'x', 1.00, -5, true)");

        // Zero is allowed for both: a free product, and an out-of-stock one.
        jdbc.update("update products set stock = 0, price = 0 where name = 'Wireless Mouse'");
    }

    @Test
    @DisplayName("CHECK: a role other than BUYER or OWNER is rejected")
    void unknownRoleIsRejected() {
        assertRejected(insertUser("eve", "eve@example.com", "ADMIN"));
        assertRejected(insertUser("eve", "eve@example.com", ""));

        jdbc.update(insertUser("alice", "alice@example.com", "BUYER"));
        jdbc.update(insertUser("boss", "boss@example.com", "OWNER"));
        assertThat(count("users")).isEqualTo(2);
    }

    @Test
    @DisplayName("UNIQUE: two accounts cannot share a username or an email")
    void duplicateUsernameAndEmailAreRejected() {
        jdbc.update(insertUser("alice", "alice@example.com", "BUYER"));

        assertRejected(insertUser("alice", "different@example.com", "BUYER"));
        assertRejected(insertUser("different", "alice@example.com", "BUYER"));
    }

    @Test
    @DisplayName("CHECK: a purchase quantity must be greater than zero")
    void nonPositiveQuantityIsRejected() {
        jdbc.update(insertUser("alice", "alice@example.com", "BUYER"));

        assertRejected(insertPurchase("alice", "Wireless Mouse", 0));
        assertRejected(insertPurchase("alice", "Wireless Mouse", -1));

        jdbc.update(insertPurchase("alice", "Wireless Mouse", 1));
        assertThat(count("purchases")).isEqualTo(1);
    }

    @Test
    @DisplayName("FOREIGN KEY: a purchase must refer to a real buyer and a real product")
    void purchaseMustReferenceRealRows() {
        jdbc.update(insertUser("alice", "alice@example.com", "BUYER"));
        Long alice = jdbc.queryForObject("select id from users where username = 'alice'", Long.class);
        Long mouse = jdbc.queryForObject("select id from products where name = 'Wireless Mouse'", Long.class);

        assertRejected("insert into purchases (buyer_id, product_id, quantity, unit_price, total_price, purchased_at) "
                + "values (" + alice + ", 999999, 1, 1.00, 1.00, current_timestamp)");
        assertRejected("insert into purchases (buyer_id, product_id, quantity, unit_price, total_price, purchased_at) "
                + "values (999999, " + mouse + ", 1, 1.00, 1.00, current_timestamp)");
    }

    @Test
    @DisplayName("FOREIGN KEY: a product that has been purchased cannot be deleted, which is why it is deactivated instead")
    void purchasedProductCannotBeDeleted() {
        jdbc.update(insertUser("alice", "alice@example.com", "BUYER"));
        jdbc.update(insertPurchase("alice", "Wireless Mouse", 1));

        assertRejected("delete from products where name = 'Wireless Mouse'");
        assertRejected("delete from users where username = 'alice'");

        // A product nobody has bought has nothing holding it in place.
        assertThat(jdbc.update("delete from products where name = 'Laptop Stand'")).isEqualTo(1);
    }

    // --- helpers ------------------------------------------------------------

    private int count(String table) {
        Integer rows = jdbc.queryForObject("select count(*) from " + table, Integer.class);
        return rows == null ? 0 : rows;
    }

    private void assertRejected(String sql) {
        assertThatThrownBy(() -> jdbc.update(sql))
                .as("expected the database to reject: %s", sql)
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static String insertUser(String username, String email, String role) {
        return "insert into users (username, email, password_hash, role, created_at) values ('"
                + username + "', '" + email + "', 'not-a-real-hash', '" + role + "', current_timestamp)";
    }

    private static String insertPurchase(String username, String productName, int quantity) {
        return "insert into purchases (buyer_id, product_id, quantity, unit_price, total_price, purchased_at) "
                + "select u.id, p.id, " + quantity + ", p.price, p.price * " + Math.abs(quantity) + ", current_timestamp "
                + "from users u, products p where u.username = '" + username + "' and p.name = '" + productName + "'";
    }
}
