# CloudShop

An online shop built for **CST-323 Cloud Computing**, Milestone 3. Each user signs
in either as a **buyer** or as the **shop owner**.

- **Buyers** register themselves, browse the catalog, buy products that are in
  stock, and see their own purchase history.
- **The owner** - exactly one account - manages the inventory and reviews every
  sale, filtered by product and date, with a revenue total.

Like the earlier [Employee Manager](https://github.com/dimijimz/cst-323-employee-manager)
project it is built to be moved between cloud platforms unchanged: everything that
varies between environments is read from environment variables, the database
builds itself with Flyway, and logs go to the console.

## Contents

- [Tech stack](#tech-stack)
- [Run it](#run-it)
- [Demo walkthrough](#demo-walkthrough)
- [Pages and routes](#pages-and-routes)
- [Environment variables](#environment-variables)
- [How a purchase works](#how-a-purchase-works)
- [Security](#security)
- [Database and migrations](#database-and-migrations)
- [Tests](#tests)
- [Design diagrams](#design-diagrams)
- [Project layout](#project-layout)
- [Deploying](#deploying)
- [Known limitations](#known-limitations)

## Tech stack

| Layer      | Choice                                                  |
|------------|---------------------------------------------------------|
| Language   | Java 17                                                 |
| Framework  | Spring Boot 3.4                                         |
| Build      | Maven (wrapper included - no local install)             |
| Web        | Spring MVC + Thymeleaf                                  |
| Styling    | Bootstrap 5 via CDN                                     |
| Security   | Spring Security form login, BCrypt, CSRF protection     |
| Data       | Spring Data JPA / Hibernate                             |
| Database   | MySQL 8 (8.0.16 or newer)                               |
| Migrations | Flyway                                                  |
| Logging    | SLF4J over Logback, console only                        |
| Health     | Spring Boot Actuator + a plain `/health`                |
| Tests      | JUnit 5, MockMvc, H2 in MySQL mode                      |

## Run it

**Prerequisite:** JDK 17 or newer (`java -version`). Maven is not required -
`mvnw` / `mvnw.cmd` download it on first use. The commands below are written for
Git Bash (`./mvnw`) and for PowerShell (`.\mvnw.cmd`).

### Option A - no database needed (the `h2` profile)

This is the quickest way to see the shop, and the one to use on a machine without
MySQL or a working Docker. The `h2` profile swaps MySQL for an in-memory H2
database running in MySQL compatibility mode. Flyway runs the **same migrations**
against it that it runs against MySQL, so you get the real schema and the eight
seed products.

Pick the owner's username and password yourself - there is no default.

Git Bash, macOS or Linux:

```bash
SPRING_PROFILES_ACTIVE=h2 OWNER_USERNAME=owner OWNER_PASSWORD='pick-your-own-password' ./mvnw spring-boot:run
```

PowerShell:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'; $env:SPRING_PROFILES_ACTIVE = 'h2'; $env:OWNER_USERNAME = 'owner'; $env:OWNER_PASSWORD = 'pick-your-own-password'; .\mvnw.cmd spring-boot:run
```

> **Windows and `JAVA_HOME`.** `mvnw.cmd` stops with "JAVA_HOME not found in your
> environment" unless `JAVA_HOME` points at a JDK, even when `java` is on the
> `PATH`. The command above sets it for the current PowerShell window only; change
> the path to wherever your JDK 17 or newer is installed. The Git Bash form does
> not need it - `./mvnw` only prints a warning and uses the `java` it finds.

Then open <http://localhost:8080>.

If startup fails with "Port 8080 was already in use" - Docker Desktop's backend is
one program that takes it - choose another port by adding `PORT=8085` to the front
of the Bash command, or `$env:PORT = '8085';` to the PowerShell one, and open that
port instead.

The database lives in memory, so **every restart starts over**: the seed products
come back, the owner is created again from the two variables, and registered
buyers and their purchases are gone.

### Option B - MySQL

Create an empty schema and a user that can create tables in it, then supply the
connection details. `DB_PASSWORD` has no default, so it must be set:

```bash
DB_HOST=localhost DB_NAME=cloudshopdb DB_USER=cloudshop_app DB_PASSWORD='...' OWNER_USERNAME=owner OWNER_PASSWORD='pick-your-own-password' ./mvnw spring-boot:run
```

On first start Flyway creates the three tables and inserts the eight products.

If your Docker works, [`docker-compose.yml`](docker-compose.yml) starts a local
MySQL 8 that matches the defaults (`docker compose up -d --wait`, then run with
`DB_PASSWORD=cloudshop_app_pw`). Nothing depends on it, and it has not been run on
the machine this project was built on - see [Known limitations](#known-limitations).

### What happens if a variable is missing

| Missing | Result |
|---------|--------|
| `DB_PASSWORD` (without the `h2` profile) | The app **refuses to start**: `IllegalStateException: DB_PASSWORD is not set`, raised before any connection is opened. |
| `OWNER_USERNAME` or `OWNER_PASSWORD` | The app starts and buyers can shop, but it logs a `WARN` and **nobody can sign in as the owner** until both are set and the app is restarted. |

### Build a jar

```bash
./mvnw clean package
```

This runs the whole test suite and produces `target/cloudshop.jar`, which takes
the same environment variables:

```bash
SPRING_PROFILES_ACTIVE=h2 OWNER_USERNAME=owner OWNER_PASSWORD='pick-your-own-password' java -jar target/cloudshop.jar
```

## Demo walkthrough

Start the app with Option A, then follow along at <http://localhost:8080>. The
seed data is set up for this: the **1080p Webcam** has exactly one unit and the
**Laptop Stand** has none.

**As a visitor (not signed in)**

1. Open **Products**. Eight products are listed with name, description, price and
   stock. The Laptop Stand is greyed out and badged **Out of stock**.
2. Open the Laptop Stand. There is no purchase form - it cannot be bought.
3. Open the Mechanical Keyboard. Instead of a quantity form you are asked to sign
   in or create a buyer account.
4. Try <http://localhost:8080/owner/sales>. You are sent to the sign-in page.

**As a buyer**

5. Choose **Register** and create an account. Try mismatched passwords first to
   see the field errors. Afterwards you land on the sign-in page with
   "Account created".
6. Sign in. You land on the catalog, and the nav bar now shows **My Purchases**.
7. Open the Mechanical Keyboard, enter quantity **2**, and choose **Purchase**.
   You are taken to **My Purchases** with a green confirmation -
   "Purchase confirmed: 2 x Mechanical Keyboard for $159.98." - and the new row is
   highlighted. Back in the catalog the keyboard now shows 23 in stock.
8. **Insufficient stock.** Open the 27-inch 4K Monitor (8 in stock) and ask for
   **99**. You are returned to the product page with a red banner: "Only 8 of
   27-inch 4K Monitor left in stock, so an order for 99 could not be placed.
   Nothing was purchased." The stock is still 8.
9. **Two buyers, one unit.** Register a second buyer in a private/incognito window.
   Open the 1080p Webcam (1 in stock) in **both** windows, so both show the
   purchase form. Choose **Purchase** in the first window - it succeeds. Choose
   **Purchase** in the second - it is refused: "1080p Webcam is out of stock.
   Nothing was purchased." Only one purchase exists.
10. Try <http://localhost:8080/owner/products>. A buyer gets **403 Forbidden**.

**As the owner**

11. Sign out, then sign in with the `OWNER_USERNAME` and `OWNER_PASSWORD` you
    started the app with. You land on **Inventory**, which lists every product.
12. **Edit** the Mechanical Keyboard and change its price to 99.00.
13. **Add Product** - give it a name, description, price and stock. It appears in
    the public catalog immediately.
14. **Deactivate** the Wireless Mouse. It stays in Inventory, marked Inactive, but
    disappears from the catalog, and its product URL now answers 404.
    **Reactivate** it to bring it back.
15. Open **Sales**. Every purchase is listed with buyer, product, quantity, unit
    price, total and time, above a **Revenue total**. Note that the keyboard sale
    from step 7 still shows the **$79.99** the buyer paid, not the new $99.00.
16. Filter by product, then by a date range, and watch the rows and the revenue
    total change together.

**Health checks**

17. <http://localhost:8080/health> returns `{"status":"UP",...}` without touching
    the database. <http://localhost:8080/actuator/health> returns the status only,
    with no component details.

## Pages and routes

| Route | Method | Who | Handled by | Purpose |
|-------|--------|-----|------------|---------|
| `/` | GET | Public | `HomeController` | Home page |
| `/products` | GET | Public | `CatalogController` | Catalog: active products with name, description, price and stock; out-of-stock ones marked |
| `/products/{id}` | GET | Public | `CatalogController` | Product detail, with the quantity form for buyers |
| `/register` | GET | Public | `AuthController` | Buyer sign-up form |
| `/register` | POST | Public | `AuthController` | Creates a buyer account |
| `/login` | GET | Public | `AuthController` | Sign-in form |
| `/login` | POST | Public | Spring Security | Checks the username and password |
| `/logout` | POST | Signed in | Spring Security | Signs out |
| `/products/{id}/purchase` | POST | **BUYER** | `PurchaseController` | Buys a quantity of a product |
| `/my-purchases` | GET | **BUYER** | `PurchaseController` | The signed-in buyer's own purchase history |
| `/owner/products` | GET | **OWNER** | `OwnerProductController` | Inventory, including inactive products |
| `/owner/products/new` | GET | **OWNER** | `OwnerProductController` | Blank product form |
| `/owner/products` | POST | **OWNER** | `OwnerProductController` | Creates a product |
| `/owner/products/{id}/edit` | GET | **OWNER** | `OwnerProductController` | Product form, pre-filled |
| `/owner/products/{id}` | POST | **OWNER** | `OwnerProductController` | Updates name, description, price and stock |
| `/owner/products/{id}/deactivate` | POST | **OWNER** | `OwnerProductController` | Hides a product from the catalog |
| `/owner/products/{id}/reactivate` | POST | **OWNER** | `OwnerProductController` | Returns it to the catalog |
| `/owner/sales` | GET | **OWNER** | `SalesController` | All purchases, with a revenue total. Optional filters: `?productId=&from=&to=` (dates are `yyyy-MM-dd`, inclusive) |
| `/health` | GET | Public | `HealthController` | Liveness probe, JSON, no database check |
| `/actuator/health` | GET | Public | Spring Boot Actuator | Readiness probe, status only |
| `/actuator/info` | GET | Public | Spring Boot Actuator | Application name and course |

Any other URL requires signing in. A missing or deactivated product answers 404;
a signed-in user without the right role gets 403; an anonymous one is sent to
`/login`.

## Environment variables

No credential or host name is compiled into the application. Every value is read
from an environment variable, defined in `src/main/resources/application.properties`.
[`.env.example`](.env.example) has the same list with longer notes.

| Variable | Default | Purpose |
|----------|---------|---------|
| `PORT` | `8080` | HTTP port the app binds to |
| `DB_HOST` | `localhost` | MySQL host name |
| `DB_PORT` | `3306` | MySQL port |
| `DB_NAME` | `cloudshopdb` | Schema name |
| `DB_USER` | `cloudshop_app` | Database user |
| `DB_PASSWORD` | **none - required** | Database password. No fallback; unset fails at startup |
| `DB_SSL_MODE` | `DISABLED` | `REQUIRED` for managed MySQL such as Azure |
| `OWNER_USERNAME` | **none** | Sign-in name for the single owner account |
| `OWNER_PASSWORD` | **none** | Password for the owner account. Stored only as a BCrypt hash |
| `OWNER_EMAIL` | `owner@cloudshop.local` | Email for the owner row (the column is required and unique) |
| `SPRING_PROFILES_ACTIVE` | *(unset)* | Set to `h2` to run without MySQL; the `DB_*` variables are then ignored |
| `DB_POOL_MAX` | `10` | Maximum Hikari pool size |
| `DB_POOL_MIN` | `2` | Minimum idle connections |
| `DB_CONNECTION_TIMEOUT_MS` | `20000` | Wait for a pooled connection before failing |
| `FLYWAY_ENABLED` | `true` | Set `false` if migrations are run separately |
| `FLYWAY_BASELINE_ON_MIGRATE` | `false` | `true` only to adopt a pre-Flyway database |
| `FLYWAY_LOG_LEVEL` | `INFO` | INFO logs which migrations ran |
| `APP_LOG_LEVEL` | `INFO` | Level for this application's own loggers |
| `JPA_SHOW_SQL` | `false` | Echo generated SQL, for debugging only |
| `THYMELEAF_CACHE` | `true` | Template caching |

**The owner account.** `OwnerAccountInitializer` runs at startup, after Flyway. If
no owner exists and both `OWNER_USERNAME` and `OWNER_PASSWORD` are set, it creates
the owner. It only ever *creates*: once an owner exists the variables are ignored,
so a redeploy can never reset the owner's password or add a second owner. There is
no owner self-registration - `/register` always creates a buyer.

**The actuator is not configurable.** Only `health` and `info` are exposed, and
`health` never shows details. Both endpoints are reachable without signing in, so
those settings are fixed in `application.properties` rather than read from the
environment.

## How a purchase works

`PurchaseService.purchase` runs in one database transaction:

1. **Decrement stock, conditionally.** A single statement both checks and takes
   the stock:

   ```sql
   UPDATE products
      SET stock = stock - :quantity
    WHERE id = :id AND active = TRUE AND stock >= :quantity
   ```

2. **If no row was updated**, there was not enough stock (or the product is gone).
   `InsufficientStockException` is thrown, nothing is recorded, and the buyer is
   returned to the product page with the reason.
3. **If one row was updated**, a `purchases` row is inserted with the unit price
   at that moment, the total, and the timestamp. Then the transaction commits.

Because the check and the decrement are one statement, there is no gap between
"read the stock" and "write the stock" for a second buyer to slip into. When two
buyers race for the last unit, the database lets one `UPDATE` match the row; the
other matches nothing. As a backstop, the `stock >= 0` CHECK constraint means the
database itself would reject an oversell even if the application code were wrong.

`unit_price` is stored on the purchase rather than joined from the product, which
is why changing a product's price later leaves past purchases exactly as they were.

## Security

- **Authentication** - Spring Security form login. `UserService` is the
  `UserDetailsService`.
- **Passwords** - hashed with BCrypt, never stored or logged in plain text.
- **Authorization** - URL rules in `SecurityConfig`: `/owner/**` needs `OWNER`;
  purchasing and `/my-purchases` need `BUYER`; the catalog, sign-in, sign-up and
  health probes are public; anything else needs a signed-in user.
- **CSRF** - enabled. Every state-changing request is a POST carrying a token.
- **Own data only** - the buyer for a purchase or a history page always comes from
  the signed-in session, never from the request.
- **Hidden links are not the access control** - the nav bar only shows what your
  role can use, but the server enforces the same rules on every request.

## Database and migrations

Flyway owns the schema; Hibernate is set to `validate` and never alters tables.
Migrations live in `src/main/resources/db/migration`:

- `V1__create_schema.sql` - `users`, `products` and `purchases` (InnoDB, utf8mb4)
  with foreign keys, unique username and email, an index on
  `purchases.purchased_at`, and CHECK constraints: `price >= 0`, `stock >= 0`,
  `quantity > 0`, `role IN ('BUYER','OWNER')`.
- `V2__seed_data.sql` - eight products and **no user accounts**.

The DDL is exported for the design report to [`docs/ddl/schema.sql`](docs/ddl/schema.sql)
(and the seed to [`docs/ddl/seed.sql`](docs/ddl/seed.sql)). Those files are
documentation; the migrations are what actually run. `DdlExportSyncTest` fails the
build if an export drifts from its migration.

To change the schema, add `V3__...sql` rather than editing an applied migration.

Products are **deactivated, never deleted**: `purchases.product_id` is a foreign
key, so deleting a product that has been sold would be refused by the database.

## Tests

```bash
./mvnw test
```

99 tests, no database or Docker needed. They run against H2 in MySQL mode, built
by Flyway from the real migrations.

| Class | What it covers |
|-------|----------------|
| `PurchaseServiceTest` | A normal purchase; insufficient stock; **a later price change does not alter past purchases**; inactive and unknown products; rollback; the sales filters; and two concurrency tests - two buyers racing for the last unit, and ten buyers for four units |
| `SecurityAccessTest` | **A buyer cannot reach `/owner/**`**; **anonymous users are sent to login**; the owner cannot purchase; CSRF; the actuator exposes only health and info, without details |
| `RegistrationTest` | **Registration** creates a BCrypt-hashed buyer who can sign in; duplicates, mismatched and weak passwords are rejected; nothing submitted can create an owner |
| `OwnerAccountInitializerTest` | The owner is created from the configured credentials, exactly once, and is never reset |
| `ShopFlowTest` | Every page rendered through MockMvc: catalog, purchase and confirmation, history, inventory, sales |
| `SchemaMigrationTest` | Both migrations apply; eight products and no accounts; each CHECK, UNIQUE and FOREIGN KEY constraint rejects bad data |
| `DdlExportSyncTest` | `docs/ddl` still matches the migrations |

## Design diagrams

In [`docs/diagrams`](docs/diagrams), as PNG with the PlantUML source beside each:

| File | Shows |
|------|-------|
| [`sitemap.png`](docs/diagrams/sitemap.png) | Every page and how they link, grouped Public / Buyer / Owner |
| [`er-diagram.png`](docs/diagrams/er-diagram.png) | The three tables, from the Flyway schema |
| [`shopping-flowchart.png`](docs/diagrams/shopping-flowchart.png) | The buyer's shopping flow, including the stock check |
| [`uml-models.png`](docs/diagrams/uml-models.png) | UML class diagram of `model` |
| [`uml-controllers.png`](docs/diagrams/uml-controllers.png) | UML class diagram of `controller` |
| [`uml-services.png`](docs/diagrams/uml-services.png) | UML class diagram of `service` |
| [`uml-repositories.png`](docs/diagrams/uml-repositories.png) | UML class diagram of `repository` |

To regenerate them all:

```bash
./docs/diagrams/render.sh
```

It needs only a JDK. PlantUML is fetched from Maven Central into `target/`, and the
diagrams use PlantUML's built-in `smetana` layout, so Graphviz is not required. The
four UML class diagrams are **generated from the compiled classes** by
`docs/diagrams/tools/UmlClassDiagrams.java`, so their fields, parameters and return
types always match the code; re-run the script after changing a class.

## Project layout

```
cloudshop/
├── pom.xml
├── mvnw, mvnw.cmd, .mvn/           Maven wrapper - no local Maven needed
├── .env.example                    every environment variable, documented
├── Procfile, system.properties     Heroku process type and JDK version
├── docker-compose.yml              optional local MySQL
├── .elasticbeanstalk/config.yml    EB CLI settings: application, region, jar artifact
├── docs/
│   ├── ddl/                        schema.sql and seed.sql, exported from the migrations
│   ├── deploy/                     AWS deployment log and the end-to-end smoke test
│   └── diagrams/                   design-report diagrams, sources and render script
└── src/
    ├── main/
    │   ├── java/edu/gcu/cst323/cloudshop/
    │   │   ├── CloudShopApplication.java
    │   │   ├── config/        SecurityConfig, OwnerAccountInitializer,
    │   │   │                  RequiredDatabasePasswordValidator
    │   │   ├── controller/    HomeController, AuthController, CatalogController,
    │   │   │                  PurchaseController, OwnerProductController,
    │   │   │                  SalesController, HealthController
    │   │   ├── exception/     InsufficientStockException, ResourceNotFoundException,
    │   │   │                  GlobalExceptionHandler
    │   │   ├── form/          RegistrationForm, ProductForm, PurchaseForm
    │   │   ├── model/         User, Role, Product, Purchase
    │   │   ├── repository/    UserRepository, ProductRepository, PurchaseRepository
    │   │   └── service/       UserService, ProductService, PurchaseService
    │   └── resources/
    │       ├── application.properties      all config externalized
    │       ├── application-h2.properties   the no-MySQL profile
    │       ├── logback-spring.xml          console-only logging
    │       ├── messages.properties         friendlier form-conversion errors
    │       ├── db/migration/               Flyway V1 schema, V2 seed
    │       ├── static/css/app.css
    │       └── templates/                  Thymeleaf views
    └── test/                               99 tests, H2 in MySQL mode
```

## Deploying

The application is one executable jar configured entirely through environment
variables:

1. Provision a managed MySQL 8 database and create an **empty** schema.
2. Set `DB_HOST`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` and `DB_SSL_MODE=REQUIRED`
   in the platform's application settings.
3. Set `OWNER_USERNAME` and `OWNER_PASSWORD` there too, as secrets.
4. Deploy the jar. Flyway builds and seeds the schema on first start, and the
   owner account is created.
5. Point the platform's health probe at `/actuator/health` (readiness) or
   `/health` (liveness).

Nothing in the source tree changes between platforms. Do not set
`SPRING_PROFILES_ACTIVE=h2` on a deployment - it would run on a throwaway
in-memory database.

### AWS

The first deployment was to AWS: Elastic Beanstalk (Java SE, Corretto 17) with RDS
for MySQL 8.4 and CloudFront in front for HTTPS.
[`docs/deploy/aws-deployment-log.md`](docs/deploy/aws-deployment-log.md) records it
command by command, with what it found along the way, what it costs to leave
running, and how to tear it down. The EB CLI settings, including the jar as the
deploy artifact, are in `.elasticbeanstalk/config.yml`.

To check a running deployment end to end - register, buy, stock, owner sign-in,
price change, sales history - run:

```bash
./docs/deploy/smoke-test.sh <base-url>
```

It reads the owner's credentials from `OWNER_USERNAME` and `OWNER_PASSWORD`, or from
the secrets file written at deployment time, and never prints them.

## Known limitations

- **Real MySQL is only exercised in the cloud.** Docker Desktop is broken on the
  development machine, so locally the migrations only ever run on H2 in MySQL
  compatibility mode, which is not MySQL. MySQL itself first ran them on
  2026-10-05, on Amazon RDS for MySQL 8.4.9, where V1 and V2 both applied unchanged
  (see [`docs/deploy/aws-deployment-log.md`](docs/deploy/aws-deployment-log.md)).
  A new migration still gets its first real test at its first deployment, so keep
  to plain, portable DDL. `docker-compose.yml` has not been run at all.
- **CHECK constraints need MySQL 8.0.16+.** Older versions accept and ignore them.
- **One owner is an application rule.** Registration can only create buyers and
  the initializer only creates an owner when none exists, but there is no database
  constraint limiting the table to one `OWNER` row.
- **Editing stock is last-write-wins.** The owner's edit form sets stock to the
  number typed. A purchase made between opening that form and saving it is
  overwritten in the stock count. Stock still can never go negative.
- **Times are in the server's time zone**, which is UTC on the usual cloud hosts.
- **One product per purchase** - there is no cart. **No password change or reset.**
  **No pagination** on the catalog or the sales report.
- **The `h2` profile is in-memory**: all data is lost on restart.
- Bootstrap is loaded from a CDN, so pages are unstyled without internet access.
