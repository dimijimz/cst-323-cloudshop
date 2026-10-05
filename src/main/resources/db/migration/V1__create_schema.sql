-- V1: base schema for CloudShop.
--
-- Flyway records this migration in flyway_schema_history, so redeploying the
-- application against an existing database is a no-op rather than an error.
--
-- Written for MySQL 8 (8.0.16 or newer, the first release that enforces CHECK
-- constraints rather than parsing and discarding them). The "h2" profile runs
-- this same file against H2 in MySQL mode, so it stays inside the syntax both
-- accept: no ENUM, no TEXT, no inline INDEX clauses.

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
