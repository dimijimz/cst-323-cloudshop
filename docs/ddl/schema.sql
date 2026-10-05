-- ===========================================================================
-- CloudShop - schema DDL (DOCUMENTATION EXPORT)
--
-- Source of truth: src/main/resources/db/migration/V1__create_schema.sql
--
-- This file exists so the design report can show the DDL script used to
-- configure the cloud database. It is a documentation export of the Flyway
-- migration, not the deployment mechanism, and it is NOT meant to be
-- run by hand against a cloud database.
--
-- Why running it by hand is actively harmful here:
--   The application applies this schema itself, with Flyway, at startup, and
--   records what it applied in a flyway_schema_history table. Executing this
--   file manually creates the tables WITHOUT that history row. Flyway runs with
--   baseline-on-migrate=false (application.properties), so on the next startup
--   it finds populated tables and no history, and refuses to start rather than
--   guessing. Recovering means dropping the tables or hand-writing a baseline.
--   Provision an EMPTY schema and let the application migrate into it.
--
-- Not included, on purpose:
--   CREATE DATABASE / USE statements. The database itself is provisioned by the
--   platform - Azure Database for MySQL Flexible Server, or the JawsDB add-on on
--   Heroku, which assigns a schema name you cannot choose. Flyway connects to
--   that existing, empty schema and builds what is below inside it.
--
-- Requires MySQL 8.0.16 or newer, the first release that enforces the CHECK
-- constraints below instead of parsing and discarding them.
--
-- To change the schema: add a new Flyway migration (V3__...), never edit V1 in
-- place, then re-export this file. DdlExportSyncTest fails the build if this
-- file and V1 drift apart.
-- ===========================================================================

CREATE TABLE users (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    username      VARCHAR(50)  NOT NULL,
    email         VARCHAR(120) NOT NULL,
    -- A BCrypt hash is 60 characters; the headroom allows a stronger encoder later.
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(10)  NOT NULL,
    created_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_users_username UNIQUE (username),
    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT chk_users_role CHECK (role IN ('BUYER', 'OWNER'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE products (
    id          BIGINT         NOT NULL AUTO_INCREMENT,
    name        VARCHAR(120)   NOT NULL,
    description VARCHAR(1000)  NOT NULL,
    price       DECIMAL(10, 2) NOT NULL,
    stock       INT            NOT NULL,
    -- Products are never deleted, because purchases reference them. The owner
    -- deactivates one instead, which removes it from the public catalog.
    active      BOOLEAN        NOT NULL DEFAULT TRUE,
    PRIMARY KEY (id),
    CONSTRAINT chk_products_price CHECK (price >= 0),
    -- Backstop for the purchase transaction: even if application code were wrong,
    -- the database itself refuses to sell a unit that does not exist.
    CONSTRAINT chk_products_stock CHECK (stock >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE purchases (
    id           BIGINT         NOT NULL AUTO_INCREMENT,
    buyer_id     BIGINT         NOT NULL,
    product_id   BIGINT         NOT NULL,
    quantity     INT            NOT NULL,
    -- The price actually paid, copied from products.price at the moment of sale.
    -- It is stored rather than joined so that a later price change cannot rewrite
    -- what a past purchase cost.
    unit_price   DECIMAL(10, 2) NOT NULL,
    total_price  DECIMAL(16, 2) NOT NULL,
    purchased_at DATETIME(6)    NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_purchases_buyer
        FOREIGN KEY (buyer_id) REFERENCES users (id),
    CONSTRAINT fk_purchases_product
        FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT chk_purchases_quantity CHECK (quantity > 0),
    CONSTRAINT chk_purchases_unit_price CHECK (unit_price >= 0),
    CONSTRAINT chk_purchases_total_price CHECK (total_price >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Serves the owner's sales report, which filters and sorts by date. No explicit
-- indexes on buyer_id or product_id: InnoDB creates one for each foreign key.
CREATE INDEX idx_purchases_purchased_at ON purchases (purchased_at);
