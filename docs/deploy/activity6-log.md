# Activity 6 log: logging, AWS redeploy, Heroku with a Loggly drain

A running record of Activity 6 for CloudShop, written as the work was done. It
follows on from [`aws-deployment-log.md`](aws-deployment-log.md), which records how
the AWS environment was built.

Three parts:

1. Add logging with an interceptor.
2. Redeploy to AWS with the new build and confirm the lines arrive.
3. Deploy to Heroku, with a log drain to Loggly.

## Status

| Part | State |
|------|-------|
| 1. Logging with an interceptor | Done. Committed as `a04a980` and pushed. A later fix to the start-up line, `3a99083`, brought the tests to 151 (4.6) |
| 2. AWS redeploy | Deployed and Green with `APP_LOG_LEVEL=DEBUG`, now on `cloudshop-1.0.0-3a99083`. ENTER, EXIT and EXCEPTION lines confirmed in `/var/log/web.stdout.log`. The signed-in smoke test is left for the operator (3.4) |
| 3. Heroku and Loggly | Live at `https://cst323-cloudshop-dj-debe1645cf87.herokuapp.com` on a Basic dyno with JawsDB's free plan. Flyway applied V1 and V2; ENTER, EXIT and EXCEPTION lines confirmed in `heroku logs`. **The first start logged the database password** (4.6): fixed, and the password has since been changed (4.11). The smoke tests and the Loggly drain are left for the operator (4.9) |
| 4. CI/CD pipeline (added later) | `cloudshop-pipeline` exists and all three stages succeed: it built, tested and deployed the footer change, which is live through CloudFront. **It does not yet start by itself on a push**; the GitHub connection was authorized without the app installation that delivers push events (5.7) |

## Ground rules

- **No secret is printed or committed.** Passwords are read from a file by shell
  substitution and never echoed. In this log they appear as `<redacted>`.
- Kept out of this file as well, because the repository is public: the AWS account
  id (`<acct>`), the RDS endpoint's unique part, database host names and email
  addresses.
- Times are local (UTC-4) unless a line carries its own offset.

## 1. Preflight (2026-10-11)

| Check | Result |
|-------|--------|
| Repository | `main` at `66a8ebd`, clean, level with `origin/main` |
| `aws sts get-caller-identity` | Failed at first: `Your session has expired`. The operator ran `aws login`; it then returned `arn:aws:iam::<acct>:user/cst323-admin`. Region `us-east-1` |
| Heroku CLI | **Not found.** Not on the registered `PATH`, not under Program Files, not among the global npm packages, not in WSL, and no `_netrc` sign-in file in the user profile |
| JDK | 17 at `C:\Program Files\Java\jdk-17`; `JAVA_HOME` is not set, so each build sets it for itself |

## 2. Part 1: logging with an interceptor

### 2.1 What was added

| File | Purpose |
|------|---------|
| `pom.xml` | `spring-boot-starter-aop` |
| `logging/LoggingAspect.java` | The interceptor: `@Around` advice on every public method in the `controller`, `service` and `repository` packages |
| `logging/ErrorCode.java` | The four error codes, and which exception maps to which |
| `logging/LogValues.java` | What may be said about an argument, a result or an exception |
| `logging/ExceptionLog.java` | Writes an exception line at the right level, with the stack trace once only |
| `exception/GlobalExceptionHandler.java` | Logs through `ExceptionLog` with the same codes; two new handlers (see 2.5) |
| `logback-spring.xml` | The agreed line pattern; console only |
| `application.properties` | `APP_LOG_LEVEL` documented; start-up banner off |

All packages below are under `edu.gcu.cst323.cloudshop`.

### 2.2 Line formats

Written by the aspect, through the logger of the class that was called:

```
ENTER ClassName.method(args)                      DEBUG
EXIT ClassName.method -> result [N ms]            DEBUG
EXCEPTION ClassName.method [CODE] Type: message   WARN, or ERROR with a stack trace
```

| Code | Raised for | Level |
|------|-----------|-------|
| `ERR-STOCK` | `InsufficientStockException` | WARN |
| `ERR-NOTFOUND` | `ResourceNotFoundException`, and `UsernameNotFoundException` at sign-in | WARN |
| `ERR-VALIDATION` | Binding and validation failures | WARN |
| `ERR-UNEXPECTED` | Everything else | ERROR, with the stack trace |

Two further lines carry the same codes: `HANDLED <status> <method> <route> [CODE] ...`
from `GlobalExceptionHandler`, and `REJECTED <method> <route> [ERR-VALIDATION] ...`
from a controller that redisplays a form.

The console pattern is
`%d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX} %-5level [%thread] %logger{36} - %msg%n`.

### 2.3 Proving the pointcut matches the repositories

The repositories are interfaces. The beans are proxy classes that Spring Data
generates (`jdk.proxy2.$Proxy...`), so nothing in the `repository` package is ever
the class being called. A throwaway test evaluated candidate pointcuts against the
real `ProductRepository` bean:

| Pointcut | Proxy advised | `findById` (inherited) | Query declared here | `toString` |
|----------|:---:|:---:|:---:|:---:|
| `within(...repository..*)` | **no** | - | - | - |
| `within(...repository..*+)` | yes | yes | yes | yes |
| `execution(public * *(..)) && !execution(* java.lang.Object.*(..)) && within(...repository..*+)` | yes | yes | yes | no |

The first row is the trap: it is the obvious thing to write, it compiles, and it
logs nothing for any repository. The last row is what `LoggingAspect` uses. The
trailing `+` admits subtypes of the types in the package, and the proxy is one
because it implements the interface.

`LoggingAspectTest` keeps the proof in the build:

- `repositoriesAreAdvisedOnEveryPublicMethod` finds the aspect's advice on each of
  the three repository beans and checks that it matches every public method of the
  interface, inherited ones included, and does not match `toString`.
- `repositoryPointcutNeedsItsSubtypeMarker` shows the pointcut applies to each
  proxy, and that the same expression without the `+` does not.
- `repositoryMethodsOfEveryKindAreLogged` calls an inherited method, a derived
  query, an `@Query` method and a `void` method, and compares the lines exactly.

Because a repository has no class of its own, its lines are written through the
logger of the repository interface, for example `e.g.c.c.repository.ProductRepository`.

### 2.4 What is never logged

- Argument values, except numbers, booleans, enums and dates. Anything else is its
  type: `username=<String>`, `form=<RegistrationForm>`. The rule goes by type and
  not by parameter name.
- Results. Type only, with the size for a collection: `<List size=8>`,
  `<Optional empty>`, `<boolean>`.
- Usernames, email addresses, passwords and password hashes. The existing log
  lines and exception messages that named a user or a product now use ids.
- A value that failed validation. Spring's own message for a binding or conversion
  failure repeats the rejected value, so `LogValues` replaces it with a summary:
  `2 validation error(s) on [email, password]`,
  `parameter 'id' could not be converted to Long`.
- The address that was requested. `HANDLED` lines log the route pattern,
  `/products/{id}`.

### 2.5 Decisions that read into the brief

1. **`UsernameNotFoundException` is `ERR-NOTFOUND`, not `ERR-UNEXPECTED`.** The
   brief maps `ERR-NOTFOUND` to `ResourceNotFoundException` and everything else to
   `ERR-UNEXPECTED`. Taken literally, every mistyped username at sign-in would be
   an ERROR with a stack trace. `UserService` has to throw that exception for
   Spring Security, and it means "no such account", so it is logged as a
   not-found at WARN. Its message no longer repeats what was typed, because
   people type passwords into the username box.
2. **`GlobalExceptionHandler` gained two handlers.**
   - A catch-all that answers an unexpected exception with a 500 page. Without it
     the exception reaches Tomcat, which prints the stack trace a second time, and
     "one stack trace per exception" cannot hold. Exceptions Spring MVC maps to a
     status itself (an unknown address is still a 404, a wrong method a 405) and
     Spring Security's own are thrown again untouched, which hands them back to
     the default handling.
   - A 400 page for binding and validation failures such as `/products/abc`.
     Before, Spring's default page for these echoed the rejected value.
3. **Results are logged as types even when they are numbers or booleans**, as the
   brief says: `EXIT ProductRepository.decrementStock -> <int>`, not `-> 1`.
4. **The per-request INFO lines in the controllers are gone.** ENTER and EXIT
   replace them. The INFO level now holds business events only: purchase
   completed, product created, product updated, price changed, product
   deactivated or reactivated, buyer registered, owner account created.
5. **The start-up banner is off**, so every line on stdout follows the pattern.
6. **The 500 and 400 pages say nothing about the failure**, and the error
   template's HTML comment naming `GlobalExceptionHandler` is now a Thymeleaf
   comment, which is not sent to the browser.

One limit is left open on purpose. The message of an unexpected exception raised
by a library is logged as the library wrote it, because that is what makes the
fault diagnosable. A database error can quote a row value: two sign-ups for the
same username at the same instant would log MySQL's duplicate-key message, which
names the duplicate.

Not built, as the brief asks: correlation ids, JSON output, CloudWatch log
streaming.

### 2.6 Tests

`./mvnw clean package`: **144 tests, 0 failures**. The 99 that existed still pass,
and none of them had to be edited.

| New class | Tests | Proves |
|-----------|:---:|--------|
| `LoggingAspectTest` | 16 | ENTER, EXIT and EXCEPTION lines, exact text, level and logger, for all three layers; the repository proof of 2.3; arguments masked; each error code; INFO business events; the pattern on stdout; 404 and 405 keep their status |
| `SensitiveDataLoggingTest` | 1 | No password, password hash, username or email in the log events or on the console, with the level at TRACE, across sign-up (accepted and refused three ways), sign-in (right, wrong, and a password typed as the username), owner creation, a purchase and lookups |
| `UnexpectedExceptionLoggingTest` | 1 | A real database failure over HTTP through a real Tomcat: one ERROR line each from repository, service, controller and handler, one stack trace, a 500 page with no detail |
| `LogValuesTest` | 12 | The masking rules case by case |
| `ErrorCodeTest` | 6 | The code for each exception; the 400 handler and `ERR-VALIDATION` cover the same exceptions |
| `ExceptionLogTest` | 5 | WARN or ERROR; the once-only stack trace, including for a wrapper of a traced exception |
| `LoggingConfigurationTest` | 4 | Console is the only appender; the pattern; the banner; the level comes from `APP_LOG_LEVEL` |

### 2.7 Mutation check

To confirm the new tests can fail, the code was broken five ways, one at a time.
Each time the relevant tests were run, then the file was restored and compared
byte for byte with its original.

| Break | Tests that failed |
|-------|:---:|
| Print argument values instead of types | 12, including `SensitiveDataLoggingTest` |
| Drop the `+` from the repository pointcut | 8 |
| Attach the stack trace every time | 4 |
| Remove the catch-all handler | 1 (`UnexpectedExceptionLoggingTest`) |
| Log Spring's own message for a binding failure | 3 |

### 2.8 Local run

The packaged jar on the `h2` profile, port 8085, `APP_LOG_LEVEL=DEBUG`, with a
throwaway owner password generated for the run. `docs/deploy/smoke-test.sh`
against it: **40 passed, 0 failed**. The script gained two checks, an unknown
product (404) and a malformed product id (400), so that one run now produces
`ERR-NOTFOUND` and `ERR-VALIDATION` lines as well as `ERR-STOCK`.

What that one smoke run put in the log:

| Line | Count |
|------|:---:|
| `ENTER` | 121 |
| `EXIT` | 117 |
| `EXCEPTION` | 4 (so 121 = 117 + 4: every call ends in one or the other) |
| `HANDLED` | 3 |
| Stack trace frames | 0 |

By code: `ERR-NOTFOUND` 3, `ERR-STOCK` 3, `ERR-VALIDATION` 1.

Searched for in that log, and found 0 times each: the owner password, the smoke
buyer's username, `@example.com`, a BCrypt hash, the wrong password the script
tries, and the malformed id it sends.

A purchase, as logged (timestamp and thread trimmed):

```
DEBUG e.g.c.c.c.PurchaseController - ENTER PurchaseController.purchase(id=2, form=<PurchaseForm>, binding=<BindingResult>, principal=<Principal>, model=<Model>, redirectAttributes=<RedirectAttributes>)
DEBUG e.g.c.c.service.PurchaseService - ENTER PurchaseService.purchase(username=<String>, productId=2, quantity=1)
DEBUG e.g.c.c.repository.ProductRepository - ENTER ProductRepository.decrementStock(id=2, quantity=1)
DEBUG e.g.c.c.repository.ProductRepository - EXIT ProductRepository.decrementStock -> <int> [14 ms]
DEBUG e.g.c.c.repository.ProductRepository - ENTER ProductRepository.findById(id=2)
DEBUG e.g.c.c.repository.ProductRepository - EXIT ProductRepository.findById -> <Optional of Product> [8 ms]
DEBUG e.g.c.c.repository.UserRepository - ENTER UserRepository.findByUsernameIgnoreCase(username=<String>)
DEBUG e.g.c.c.repository.UserRepository - EXIT UserRepository.findByUsernameIgnoreCase -> <Optional of User> [1 ms]
DEBUG e.g.c.c.r.PurchaseRepository - ENTER PurchaseRepository.save(entity=<Purchase>)
DEBUG e.g.c.c.r.PurchaseRepository - EXIT PurchaseRepository.save -> <Purchase> [1 ms]
INFO  e.g.c.c.service.PurchaseService - Purchase completed: purchaseId=1 buyerId=2 productId=2 quantity=1 unitPrice=24.99 total=24.99 stockLeft=39
DEBUG e.g.c.c.service.PurchaseService - EXIT PurchaseService.purchase -> <Purchase> [31 ms]
DEBUG e.g.c.c.c.PurchaseController - EXIT PurchaseController.purchase -> <String> [38 ms]
```

An order for more than is in stock:

```
WARN  e.g.c.c.service.PurchaseService - EXCEPTION PurchaseService.purchase [ERR-STOCK] InsufficientStockException: Only 39 of Wireless Mouse left in stock, so an order for 1039 could not be placed. Nothing was purchased.
WARN  e.g.c.c.c.PurchaseController - EXCEPTION PurchaseController.purchase [ERR-STOCK] InsufficientStockException: Only 39 of Wireless Mouse left in stock, so an order for 1039 could not be placed. Nothing was purchased.
WARN  e.g.c.c.e.GlobalExceptionHandler - HANDLED 302 POST /products/{id}/purchase [ERR-STOCK] InsufficientStockException: Only 39 of Wireless Mouse left in stock, so an order for 1039 could not be placed. Nothing was purchased.
```

Run again with `APP_LOG_LEVEL` unset: 0 `ENTER`, 0 `EXIT`, and the `EXCEPTION` and
`HANDLED` lines still there, so the default level is INFO and exceptions do not
depend on DEBUG. With the banner off, every line of that log matched the pattern.

### 2.9 Other changes in the same commit

- `Procfile` and `system.properties` already fitted CloudShop: `target/cloudshop.jar`
  and Java 17. Only their comments changed, which described Azure, a platform this
  project never used. The same for `.env.example` and `application.properties`.
- The four UML class diagrams were regenerated and came out identical, so they are
  still accurate: the generator does not draw logger fields, and no method or
  field in `controller`, `service`, `repository` or `model` changed.

## 3. Part 2: AWS redeploy (2026-10-11 evening; 00:55 UTC on the 12th)

### 3.1 Before

| Resource | State |
|----------|-------|
| Elastic Beanstalk `cloudshop-env` | Ready, Green, version `cloudshop-1.0.0-0c20f11` |
| RDS `cloudshop-db` | available, MySQL 8.4.9, `db.t3.micro`, not public |
| CloudFront `E39LB8FY296PK2` | Deployed, enabled |
| Site | `/health`, `/actuator/health` and `/products` all 200 |

### 3.2 Commands

```
eb setenv APP_LOG_LEVEL=DEBUG -e cloudshop-env
./mvnw clean package
eb deploy cloudshop-env --label cloudshop-1.0.0-a04a980 --message "Activity 6: logging aspect, APP_LOG_LEVEL=DEBUG"
```

| Step | Result (times in UTC) |
|------|-----------------------|
| `eb setenv` | 00:55:46 update started; 00:56:47 `Successfully deployed new configuration to environment` |
| `./mvnw clean package` | 144 tests, `BUILD SUCCESS`; `target/cloudshop.jar` is the build of commit `a04a980` |
| `eb deploy` | 00:56:59 update started; 00:57:14 `Instance deployment completed successfully`; 00:57:20 `Environment update completed successfully` |
| Health | Yellow for under a minute while the application restarted, then Green: `Application update completed ... and took 18 seconds` |

The EB CLI also printed `Alert: The platform version that your environment is using
isn't recommended. There's a recommended version in the same platform branch.` The
environment is on `64bit Amazon Linux 2023 v4.12.9 running Corretto 17`. It was
left there: a platform upgrade is not part of this activity.

No application code or migration changed for the platform. The only configuration
change is the one environment variable.

### 3.3 Checked without signing in, through CloudFront

| Request | Status |
|---------|:---:|
| `GET /health` | 200 |
| `GET /actuator/health` | 200 |
| `GET /products` | 200 |
| `GET /products/1` | 200 |
| `GET /products/999999999` | 404, page says `Product 999999999 was not found` |
| `GET /products/not-a-number` | 400, page says the value could not be used and does not repeat it |

Then `eb logs --all` downloaded the instance's logs, and
`/var/log/web.stdout.log` was read from the bundle.

Start-up after the redeploy, as logged (the RDS host is masked here):

```
2026-10-12T00:57:21.566Z INFO  [main] org.flywaydb.core.FlywayExecutor - Database: jdbc:mysql://<rds-endpoint>/cloudshop?sslMode=REQUIRED&... (MySQL 8.4)
2026-10-12T00:57:21.758Z INFO  [main] o.f.core.internal.command.DbValidate - Successfully validated 2 migrations (execution time 00:00.103s)
2026-10-12T00:57:21.799Z INFO  [main] o.f.core.internal.command.DbMigrate - Current version of schema `cloudshop`: 2
2026-10-12T00:57:21.804Z INFO  [main] o.f.core.internal.command.DbMigrate - Schema `cloudshop` is up to date. No migration necessary.
2026-10-12T00:57:28.936Z INFO  [main] e.g.c.c.c.OwnerAccountInitializer - Owner account already exists; OWNER_USERNAME and OWNER_PASSWORD were not applied
```

**ENTER, EXIT and EXCEPTION lines are in `/var/log/web.stdout.log`.** From the six
requests above and the start-up: 18 `ENTER`, 14 `EXIT`, 4 `EXCEPTION`, 4 `HANDLED`.
By code: `ERR-NOTFOUND` 6, `ERR-VALIDATION` 2. (The missing-product and
malformed-id requests were each made twice.)

The platform puts its own prefix in front of each line:

```
Oct 12 00:57:36 ip-<private> web[364254]: 2026-10-12T00:57:36.816Z DEBUG [http-nio-5000-exec-3] ...
```

The catalog request, with that prefix removed:

```
2026-10-12T00:57:36.816Z DEBUG [http-nio-5000-exec-3] e.g.c.c.controller.CatalogController - ENTER CatalogController.list(model=<Model>)
2026-10-12T00:57:36.816Z DEBUG [http-nio-5000-exec-3] e.g.c.c.service.ProductService - ENTER ProductService.findCatalog()
2026-10-12T00:57:36.823Z DEBUG [http-nio-5000-exec-3] e.g.c.c.repository.ProductRepository - ENTER ProductRepository.findByActiveTrueOrderByNameAsc()
2026-10-12T00:57:36.855Z DEBUG [http-nio-5000-exec-3] e.g.c.c.repository.ProductRepository - EXIT ProductRepository.findByActiveTrueOrderByNameAsc -> <List size=7> [31 ms]
2026-10-12T00:57:36.859Z DEBUG [http-nio-5000-exec-3] e.g.c.c.service.ProductService - EXIT ProductService.findCatalog -> <List size=7> [42 ms]
2026-10-12T00:57:36.860Z DEBUG [http-nio-5000-exec-3] e.g.c.c.controller.CatalogController - EXIT CatalogController.list -> <String> [43 ms]
```

The missing product:

```
2026-10-12T00:57:37.885Z DEBUG [http-nio-5000-exec-5] e.g.c.c.controller.CatalogController - ENTER CatalogController.detail(id=999999999, model=<Model>)
2026-10-12T00:57:37.886Z DEBUG [http-nio-5000-exec-5] e.g.c.c.service.ProductService - ENTER ProductService.findActiveById(id=999999999)
2026-10-12T00:57:37.888Z DEBUG [http-nio-5000-exec-5] e.g.c.c.repository.ProductRepository - ENTER ProductRepository.findByIdAndActiveTrue(id=999999999)
2026-10-12T00:57:37.893Z DEBUG [http-nio-5000-exec-5] e.g.c.c.repository.ProductRepository - EXIT ProductRepository.findByIdAndActiveTrue -> <Optional empty> [4 ms]
2026-10-12T00:57:37.902Z WARN  [http-nio-5000-exec-5] e.g.c.c.service.ProductService - EXCEPTION ProductService.findActiveById [ERR-NOTFOUND] ResourceNotFoundException: Product 999999999 was not found
2026-10-12T00:57:37.902Z WARN  [http-nio-5000-exec-5] e.g.c.c.controller.CatalogController - EXCEPTION CatalogController.detail [ERR-NOTFOUND] ResourceNotFoundException: Product 999999999 was not found
2026-10-12T00:57:37.908Z WARN  [http-nio-5000-exec-5] e.g.c.c.e.GlobalExceptionHandler - HANDLED 404 GET /products/{id} [ERR-NOTFOUND] ResourceNotFoundException: Product 999999999 was not found
```

The malformed id:

```
2026-10-12T00:57:38.080Z WARN  [http-nio-5000-exec-6] e.g.c.c.e.GlobalExceptionHandler - HANDLED 400 GET /products/{id} [ERR-VALIDATION] MethodArgumentTypeMismatchException: parameter 'id' could not be converted to Long
```

The catalog holds seven products, not the eight that were seeded, so one has been
deactivated through the owner's inventory page since the first deployment.

Searched for in the downloaded log and found 0 times each: the owner password, the
database password, a BCrypt hash, and any email address among the new lines. The
downloaded bundle was deleted afterwards; `.elasticbeanstalk/logs/` is ignored by
git in any case.

### 3.4 Smoke test: left for the operator

`docs/deploy/smoke-test.sh` registers an account and signs in with the owner's
password. As with the first deployment, the assistant doing this work does not
create accounts on, or sign in to, a site that is not running on the local
machine, so it has not run the script against the deployment; it ran it against
the same build locally (2.8). To run it against AWS, from Git Bash in the
repository:

```
./docs/deploy/smoke-test.sh https://d2m8eu1qwn32t4.cloudfront.net
```

It reads the owner's password from `~/.cloudshop/aws-secrets.env` and prints
neither it nor the buyer's. With `APP_LOG_LEVEL=DEBUG` the run adds `ERR-STOCK`
lines to the log, along with the sign-in, purchase and price-change traces.

`APP_LOG_LEVEL` is still `DEBUG` on the environment. To put it back:

```
eb setenv APP_LOG_LEVEL=INFO -e cloudshop-env
```

### 3.5 Cost

Unchanged by this part: the same instance and database as before, about
$0.87 a day from the Free-plan credits (see `aws-deployment-log.md`, section 9).
DEBUG logging adds lines to a file on the instance's existing disk, which the
platform rotates.

## 4. Part 3: Heroku and Loggly

### 4.1 Preflight: blocked

`heroku auth:whoami` could not be run, because there is no `heroku` command on
this machine:

| Looked in | Found |
|-----------|-------|
| The machine and user `PATH` as registered in Windows | Node.js and npm only |
| `Program Files`, `Program Files (x86)`, `%LOCALAPPDATA%`, `%APPDATA%\npm` | No Heroku CLI |
| Global npm packages | No `heroku` |
| WSL (Ubuntu) | No `heroku` |
| `%USERPROFILE%\_netrc` and `.netrc`, where `heroku login` stores the sign-in | Neither exists |

Nothing was created on Heroku. Part 3 resumes when the CLI is installed and
`heroku login` has been run.

### 4.2 What was looked up in the meantime

| Question | Answer | Source |
|----------|--------|--------|
| Is there a free MySQL plan? | Yes: JawsDB **Kitefin Shared**, free. 5 MB of storage, 10 connections, 3,600 queries an hour | Heroku Elements, JawsDB page; Heroku Dev Center, JawsDB article |
| What does a Basic dyno cost? | About $0.01 an hour, at most $7 a month, prorated to the second. Scaled to 0 it accrues nothing | Heroku Dev Center, Usage and Billing |
| And Eco? | A flat $5 for the month, charged in full whenever in the month it is started | Same |
| `JAWSDB_URL` format | `mysql://username:password@hostname:port/default_schema` | Heroku Dev Center, JawsDB article |

Planned from that, not yet done: `DB_POOL_MAX=4`, so that the old and the new dyno
can overlap during a restart (2 x 4 = 8) and still stay under the cap of 10.

### 4.3 CLI installed and signed in; app creation refused

The operator installed the CLI with `npm install -g heroku` and ran `heroku login`.

| Check | Result |
|-------|--------|
| `heroku --version` | `heroku/11.11.0 win32-x64 node-v20.15.0` |
| npm's `EBADENGINE` warnings during the install | Harmless here. Some dependencies ask for Node 20.17 to 20.19; the CLI itself asks only for Node 20. Each package npm named was loaded on this Node and loads |
| `heroku auth:whoami` | Signed in (address not recorded here) |
| `heroku apps` | `You have no apps.` |
| `heroku addons:plans jawsdb` | `jawsdb:kitefin`, Kitefin Shared, is the default plan and is listed as `free`. The next plan up is `jawsdb:leopard` at about $0.014 an hour, $10 a month at most |

The operator was shown the cost and approved it: one Basic dyno at about $0.01 an
hour, at most $7 a month, and the free JawsDB plan.

```
heroku create cst323-cloudshop-dj
```

```
Creating cst323-cloudshop-dj... !
Error: To create an app, verify your account by adding payment information.
Verify now at https://heroku.com/verify
Error ID: verification_required
```

Nothing was created. Adding a payment method is the account holder's step; Part 3
resumes after it.

### 4.4 App, database and config vars (02:47 UTC on 2026-10-12)

The operator added a payment method, and the same command then worked.

| Step | Command | Result |
|------|---------|--------|
| App | `heroku create cst323-cloudshop-dj` | Created under that name. `https://cst323-cloudshop-dj-debe1645cf87.herokuapp.com/`, region `us`, stack `heroku-24`. It also added the `heroku` git remote |
| Database | `heroku addons:create jawsdb:kitefin` | `jawsdb-graceful-69813`, plan Kitefin Shared, price `free`. `heroku addons:wait` returned in under a minute with `JAWSDB_URL` set |
| Config vars | `APP_LOG_LEVEL=DEBUG ./docs/deploy/heroku-config.sh cst323-cloudshop-dj` | See below |

[`heroku-config.sh`](heroku-config.sh) is new. It reads `JAWSDB_URL`
(`mysql://user:password@host:port/schema`), splits it into the five `DB_*`
variables CloudShop reads on every platform, creates the owner password, and sets
everything in one `heroku config:set`. It prints variable names only; Heroku's
own commands echo values, so their output is captured or discarded.

| Config var | Value |
|------------|-------|
| `DB_HOST`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` | From `JAWSDB_URL`. Each was compared with the URL afterwards and matches |
| `DB_PORT` | `3306` |
| `DB_SSL_MODE` | `REQUIRED`. **JawsDB accepts it** (4.7) |
| `DB_POOL_MAX` | `4`. The plan allows 10 connections; 4 lets an old and a new dyno overlap during a restart and stay under it |
| `OWNER_USERNAME` | `owner` |
| `OWNER_PASSWORD` | `<redacted>`: 24 random letters and digits, generated by the script into `%USERPROFILE%\.cloudshop\heroku-secrets.env`, outside the repository and outside OneDrive |
| `APP_LOG_LEVEL` | `DEBUG` |
| `DISABLE_SPRING_DATASOURCE_URL` | `true`. Added after the first start; see 4.6 |
| `JAWSDB_URL` | Set by the add-on; left alone |

`Procfile` and `system.properties` needed no change for Heroku:
`web: java -Dserver.port=$PORT -jar target/cloudshop.jar` and
`java.runtime.version=17`.

### 4.5 Deploy

```
git push heroku main
```

```
-----> Building on the Heroku-24 stack
-----> Java app detected
-----> Installing Azul Zulu OpenJDK 17.0.20.1
-----> Executing Maven
       [INFO] Tests are skipped.
       [INFO] BUILD SUCCESS
       [INFO] Total time:  20.138 s
-----> Discovering process types
       Procfile declares types -> web
-----> Compressing...
       Done: 124.1M
-----> Launching...
       Released v5
```

The Heroku CLI 11 keeps its sign-in in its own store, not in `_netrc`, and
registers itself with git as the credential helper for `git.heroku.com`, so the
push needed no token on the command line.

`heroku ps:type` afterwards:

```
type   size    qty   cost/hour   max cost/month
web    Basic   1     ~$0.010     $7
```

**Flyway applied V1 and V2**, on the first start, to JawsDB's MySQL 8.4. Neither
migration needed a change. From `heroku logs` (schema name and host masked):

```
2026-10-12T02:50:12.099Z INFO  [main] o.f.core.internal.command.DbValidate - Successfully validated 2 migrations (execution time 00:00.027s)
2026-10-12T02:50:12.245Z INFO  [main] o.f.core.internal.command.DbMigrate - Current version of schema `<schema>`: << Empty Schema >>
2026-10-12T02:50:12.263Z INFO  [main] o.f.core.internal.command.DbMigrate - Migrating schema `<schema>` to version "1 - create schema"
2026-10-12T02:50:12.522Z INFO  [main] o.f.core.internal.command.DbMigrate - Migrating schema `<schema>` to version "2 - seed data"
2026-10-12T02:50:12.564Z INFO  [main] o.f.core.internal.command.DbMigrate - Successfully applied 2 migrations to schema `<schema>`, now at version v2 (execution time 00:00.215s)
2026-10-12T02:50:15.778Z INFO  [main] e.g.c.cloudshop.CloudShopApplication - Started CloudShopApplication in 6.705 seconds (process running for 7.14)
2026-10-12T02:50:16.062Z INFO  [main] e.g.c.cloudshop.service.UserService - Owner account created: userId=1
2026-10-12T02:50:16.074Z INFO  [main] e.g.c.c.c.OwnerAccountInitializer - The owner account was created from OWNER_USERNAME and OWNER_PASSWORD
```

### 4.6 Incident: the database password was logged at the first start

**What happened.** The first start on Heroku wrote the JawsDB password, in clear
text, to the application log. It was one line, the application's own start-up
line, `CloudShop started on port ... against datasource <url>`.

**Why.** Two things had to be true together.

1. Heroku's Java buildpack reads `JAWSDB_URL` itself. At dyno start it exports
   `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` and
   `SPRING_DATASOURCE_PASSWORD`, and the URL it builds carries the user and the
   password in its query string. An environment variable outranks
   `application.properties`, so that URL replaced the application's own. The five
   `DB_*` variables set in 4.4 were being ignored, and so was `sslMode=REQUIRED`.
2. The start-up line logged `spring.datasource.url` whole. Its comment said the
   line was "logged without credentials", which was true of the URL this
   application builds and false of the one it was handed.

This did not happen on AWS, where nothing injects a URL and the application's own
carries no credentials.

**Where the value went.**

| Place | State |
|-------|-------|
| Heroku's log buffer for the app | One line holds the password. It cannot be deleted from there; it drops out when 1,500 newer lines have arrived or after about a week. Only the account can read it |
| The assistant's session on the operator's machine | The line was read back while checking the logs, so the password is in that session's transcript |
| A scratch copy of the log | Deleted |
| This repository, this log, the commits | Not present. Each commit was searched for the database password, user, schema, host and the owner password before it was made |
| Loggly | Never sent. No drain existed |

In the same start, Flyway's own `Database:` line printed the URL with the password
replaced by asterisks but the database user in clear.

**What was done.**

| Fix | Kind | Effect |
|-----|------|--------|
| `heroku config:set DISABLE_SPRING_DATASOURCE_URL=true` | Platform configuration | The buildpack leaves the datasource alone. The application now builds its own URL from the `DB_*` variables, with `sslMode=REQUIRED`, and the URL carries no credentials. Release v6 |
| `CloudShopApplication.describeDatasource` | Code, commit `3a99083` | The start-up line logs only the address: no query string, no `;` settings, no `user:password@`. A URL it cannot take apart is withheld. `StartupLineTest`, 7 tests. Release v7 on Heroku; redeployed to AWS as `cloudshop-1.0.0-3a99083` so both run the same build |
| `heroku-config.sh` sets the switch | Deploy script | A second Heroku app set up from this repository does not repeat step 1 |

The code change is a fix to a logging defect, not an adaptation to the platform:
the line was unsafe wherever a URL with credentials reached it. Tests went from
144 to 151.

The start-up line now reads:

```
CloudShop started on port 33355 against datasource jdbc:mysql://<jawsdb-host>/<schema>
```

**Still open: the password itself should be changed**, because it has been
written down in two places. That is the operator's step (4.9).

### 4.7 Checks after the fix

`DB_SSL_MODE=REQUIRED` is in effect and accepted. Flyway's line after the switch:

```
2026-10-12T02:52:58.633Z INFO  [main] org.flywaydb.core.FlywayExecutor - Database: jdbc:mysql://<jawsdb-host>/<schema>?sslMode=REQUIRED&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8 (MySQL 8.4)
2026-10-12T02:52:58.742Z INFO  [main] o.f.core.internal.command.DbMigrate - Current version of schema `<schema>`: 2
2026-10-12T02:52:58.746Z INFO  [main] o.f.core.internal.command.DbMigrate - Schema `<schema>` is up to date. No migration necessary.
```

Requests that need no sign-in, over HTTPS:

| Request | Status |
|---------|:---:|
| `GET /health` | 200 |
| `GET /actuator/health` | 200 |
| `GET /` | 200 |
| `GET /products` | 200, eight products |
| `GET /products/1` | 200 |
| `GET /products/999999999` | 404 |
| `GET /products/not-a-number` | 400 |
| `GET /owner/sales` | 302 to sign-in |

**ENTER, EXIT and EXCEPTION lines are in `heroku logs`.** Heroku puts its own
timestamp and the dyno name in front of each line:

```
2026-10-12T02:58:25.643366+00:00 app[web.1]: 2026-10-12T02:58:25.643Z DEBUG [http-nio-33355-exec-2] e.g.c.c.controller.CatalogController - ENTER CatalogController.list(model=<Model>)
2026-10-12T02:58:25.643419+00:00 app[web.1]: 2026-10-12T02:58:25.643Z DEBUG [http-nio-33355-exec-2] e.g.c.c.service.ProductService - ENTER ProductService.findCatalog()
2026-10-12T02:58:25.645334+00:00 app[web.1]: 2026-10-12T02:58:25.645Z DEBUG [http-nio-33355-exec-2] e.g.c.c.repository.ProductRepository - ENTER ProductRepository.findByActiveTrueOrderByNameAsc()
2026-10-12T02:58:25.649076+00:00 app[web.1]: 2026-10-12T02:58:25.648Z DEBUG [http-nio-33355-exec-2] e.g.c.c.repository.ProductRepository - EXIT ProductRepository.findByActiveTrueOrderByNameAsc -> <List size=8> [3 ms]
2026-10-12T02:58:25.650841+00:00 app[web.1]: 2026-10-12T02:58:25.650Z DEBUG [http-nio-33355-exec-2] e.g.c.c.service.ProductService - EXIT ProductService.findCatalog -> <List size=8> [7 ms]
2026-10-12T02:58:25.650876+00:00 app[web.1]: 2026-10-12T02:58:25.650Z DEBUG [http-nio-33355-exec-2] e.g.c.c.controller.CatalogController - EXIT CatalogController.list -> <String> [7 ms]
```

```
2026-10-12T02:58:25.144401+00:00 app[web.1]: 2026-10-12T02:58:25.144Z WARN  [http-nio-33355-exec-10] e.g.c.c.service.ProductService - EXCEPTION ProductService.findActiveById [ERR-NOTFOUND] ResourceNotFoundException: Product 999999999 was not found
2026-10-12T02:58:25.144502+00:00 app[web.1]: 2026-10-12T02:58:25.144Z WARN  [http-nio-33355-exec-10] e.g.c.c.controller.CatalogController - EXCEPTION CatalogController.detail [ERR-NOTFOUND] ResourceNotFoundException: Product 999999999 was not found
2026-10-12T02:58:25.147189+00:00 app[web.1]: 2026-10-12T02:58:25.147Z WARN  [http-nio-33355-exec-10] e.g.c.c.e.GlobalExceptionHandler - HANDLED 404 GET /products/{id} [ERR-NOTFOUND] ResourceNotFoundException: Product 999999999 was not found
```

One thing the application does not control: Heroku's router writes a line of its
own for every request, and that line holds the address exactly as requested and
the visitor's IP address.

```
heroku[router]: at=info method=GET path="/products/999999999" host=cst323-cloudshop-dj-debe1645cf87.herokuapp.com request_id=<id> fwd="<ip>" dyno=web.1 connect=0ms service=47ms status=404 ...
```

A log drain receives those router lines as well as the application's.

### 4.8 AWS, redeployed with the same fix

```
eb deploy cloudshop-env --label cloudshop-1.0.0-3a99083 --message "Start-up line no longer logs the datasource URL's credentials"
```

02:57:13 UTC update started; 02:57:36 `Environment update completed successfully`.
Ready, Green, `/actuator/health` 200 through CloudFront. `APP_LOG_LEVEL` is still
`DEBUG` there.

### 4.9 Left for the operator

1. **Change the database password.** `heroku addons:open jawsdb -a cst323-cloudshop-dj`
   opens JawsDB's dashboard, which has a password reset. Then run
   `APP_LOG_LEVEL=DEBUG ./docs/deploy/heroku-config.sh cst323-cloudshop-dj` again
   so the `DB_*` variables pick up the new value from `JAWSDB_URL`.
2. **Run the smoke test against each deployment.** As in 3.4, the assistant does
   not register or sign in on a deployed site.

   ```
   ./docs/deploy/smoke-test.sh https://d2m8eu1qwn32t4.cloudfront.net
   SECRETS_FILE=~/.cloudshop/heroku-secrets.env ./docs/deploy/smoke-test.sh https://cst323-cloudshop-dj-debe1645cf87.herokuapp.com
   ```

3. **Add the Loggly drain.** The assistant does not handle the Loggly token, so
   this command is the operator's to run, with the customer token in place of
   `<TOKEN>`:

   ```
   heroku drains:add https://logs-01.loggly.com/bulk/<TOKEN>/tag/cst323_logfile_heroku_upload --app cst323-cloudshop-dj
   ```

   The token stays out of this repository and this log. `heroku drains` prints the
   drain's address, token included, so its output should not be pasted anywhere.

### 4.10 What Heroku is billing

| Resource | Rate |
|----------|------|
| One Basic dyno, `web.1`, running since 02:50 UTC on 2026-10-12 | About $0.010 an hour, prorated to the second, at most $7 a month |
| JawsDB Kitefin Shared | Free |
| Log drain | Free on Heroku's side |

To stop the charge and keep the app: `heroku ps:scale web=0 -a cst323-cloudshop-dj`.
To remove everything, database included: `heroku apps:destroy cst323-cloudshop-dj`.

### 4.11 The database password was changed (03:07 UTC)

The operator reset the password in JawsDB's dashboard. What followed is worth
knowing before doing it again:

| Time (UTC) | Event |
|------------|-------|
| 03:07:55 | JawsDB updated `JAWSDB_URL` itself. Heroku recorded it as release v8, `Update JAWSDB by jawsdb`, and restarted the dyno |
| 03:08:05 | The dyno started with the new `JAWSDB_URL` but the old `DB_PASSWORD`, which is the copy the application reads. Flyway could not connect (`Access denied for user ... (using password: YES)`), and the process exited. Heroku restarted it and it crashed again: the site answered 503 |
| 03:12 | `APP_LOG_LEVEL=DEBUG ./docs/deploy/heroku-config.sh cst323-cloudshop-dj` re-read `JAWSDB_URL` and set `DB_PASSWORD` to match |
| 03:12:59 | Dyno up. `/actuator/health` 200, `/products` 200 with eight products; the data was untouched by the reset |

So the site was down for about five minutes, the gap between the reset and the
script. That is the cost of keeping a split copy of the URL: **after any JawsDB
credential change, run `heroku-config.sh` straight away.**

Checked afterwards, by count only: the new password appears nowhere in
`heroku logs`, and neither does the owner password. The one old line that held
the previous password is still in the buffer, and that password no longer works.

## 5. CI/CD: AWS CodePipeline to Elastic Beanstalk

Added after the three parts above. Every push to `main` on
`github.com/dimijimz/cst-323-cloudshop` is to build, test and deploy itself to
`cloudshop-env`.

### 5.1 Design

```
push to main --> Source (GitHub, through a CodeConnections connection)
             --> Build  (CodeBuild: Corretto 17, ./mvnw -B clean package, tests included)
             --> Deploy (Elastic Beanstalk: application cloudshop, environment cloudshop-env)
```

| Resource | Name | Notes |
|----------|------|-------|
| Pipeline | `cloudshop-pipeline` | Type V2, execution mode SUPERSEDED, push trigger on branch `main` |
| GitHub connection | `cloudshop-github` | AWS CodeConnections. Created `PENDING`; the account holder approves it in the console (5.4) |
| Build project | `cloudshop-build` | `BUILD_GENERAL1_SMALL`, the smallest on-demand type. Image `aws/codebuild/amazonlinux-x86_64-standard:5.0`. 15-minute timeout, one build at a time |
| Artifact bucket | `cloudshop-pipeline-artifacts-aecebd27` | The only bucket created. Private: all four public-access blocks on, SSE-S3 encryption |
| Build role | `cloudshop-codebuild-role` | One inline policy, no managed policies |
| Pipeline role | `cloudshop-pipeline-role` | One inline policy, no managed policies |
| Build log group | `/aws/codebuild/cloudshop-build` | 14-day retention |

The definitions these were created from are in [`pipeline/`](pipeline), with the
account id, the bucket name and the connection id replaced by placeholders.

**What Elastic Beanstalk receives is one file.** [`buildspec.yml`](../../buildspec.yml)
declares the artifact as `target/*.jar` with `discard-paths: yes`, so the bundle
is `cloudshop.jar` at its root and nothing else. The Java SE platform runs a
bundle like that with `java -jar`. The repository's `Procfile` is not in it: that
file is Heroku's, it starts `target/cloudshop.jar`, and a `Procfile` in the
bundle would replace the one the platform generates.

**A failing test stops the pipeline.** Tests run inside `package`. A failure fails
Maven, Maven's exit code fails the build, and a failed Build stage never reaches
Deploy.

### 5.2 Scoping the roles

"Scoped to these resources" needed evidence for the pipeline role, because
Elastic Beanstalk performs part of a deploy with the caller's permissions, and
the policy AWS's console attaches for this is `elasticbeanstalk:*`, `ec2:*`,
`s3:*`, `cloudformation:*` and more on every resource.

CloudTrail had the answer. It records what Elastic Beanstalk called on behalf of
the caller during the `eb deploy` at 02:57 UTC, a real deploy of this
environment:

| Service | Calls made on the caller's behalf |
|---------|-----------------------------------|
| CloudFormation | `DescribeStacks`, `DescribeStackEvents`, `DescribeStackResource`, `DescribeStackResources`, `ListStackResources`, `GetTemplate` |
| Auto Scaling | `DescribeAutoScalingGroups`, `DescribeScalingActivities`, `SuspendProcesses`, `ResumeProcesses` |
| EC2 | `DescribeLaunchTemplates`, `DescribeLaunchTemplateVersions` |
| S3 | `GetBucketLocation`, `GetBucketPolicy` on Elastic Beanstalk's own bucket |

No stack update, no instance launch: deploying a new version to a running
environment reads its stack, pauses scaling, and resumes it.

[`pipeline/pipeline-role-policy.json`](pipeline/pipeline-role-policy.json) grants
those calls and scopes each one as far as IAM allows:

| Statement | Limited to |
|-----------|------------|
| Artifact objects | `cloudshop-pipeline/*` in the artifact bucket |
| Use the GitHub connection | The one connection |
| Start and read builds | The project `cloudshop-build` |
| Create a version, update the environment, read events | Application `cloudshop`, its versions, environment `cloudshop-env` |
| Elastic Beanstalk's storage | The bucket `elasticbeanstalk-us-east-1-<acct>` |
| Read the stack | The environment's stack, `awseb-e-ymbnrtr5ck-stack` |
| Suspend and resume scaling | The environment's scaling group, `awseb-e-ymbnrtr5ck-stack-*` |
| Four `Describe` calls | `*`, because those actions do not accept a resource |

[`pipeline/codebuild-role-policy.json`](pipeline/codebuild-role-policy.json) is
shorter: write to its own log group, and read and write objects under
`cloudshop-pipeline/*` in the artifact bucket.

Both roles can be assumed only by their own service, and only from this account
(`aws:SourceAccount`).

### 5.3 CodeBuild works on this account

New accounts are sometimes refused with `Cannot have more than 0 builds in queue`.
To find out before depending on it, the project was run once on its own against
the public repository, with no artifact:

```
aws codebuild start-build --project-name cloudshop-build \
    --source-type-override GITHUB \
    --source-location-override https://github.com/dimijimz/cst-323-cloudshop.git \
    --source-version main --artifacts-override type=NO_ARTIFACTS
```

It ran, and succeeded in about 70 seconds from start to finish.

| Phase | Seconds |
|-------|:---:|
| Provisioning | 4 |
| Download source | 3 |
| Build (`./mvnw -B clean package`) | 56 |

From its log:

```
Selecting 'java' runtime version 'corretto17' based on manual selections...
Running command chmod +x mvnw
Running command ./mvnw -B clean package
[INFO] Tests run: 151, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] Total time:  48.946 s
```

So the GitHub Actions fallback is not needed.

### 5.4 Waiting on the connection

`aws codeconnections create-connection --provider-type GitHub --connection-name cloudshop-github`
returns a connection in `PENDING`. Only a person signed in to both AWS and GitHub
can complete it, in the console:

1. Developer Tools, Settings, Connections
   (`https://us-east-1.console.aws.amazon.com/codesuite/settings/connections?region=us-east-1`).
2. Select `cloudshop-github`, then **Update pending connection**.
3. In the window that opens, sign in to GitHub if asked and authorize
   **AWS Connector for GitHub**.
4. **Install a new app**, choose the account, **Only select repositories**,
   pick `cst-323-cloudshop`, **Install**.
5. Back in the AWS window, **Connect**. The status becomes `AVAILABLE`.

The pipeline itself is created after that, so that its first execution is not a
failed one.

### 5.5 Pipeline created; first execution (14:56 UTC on 2026-10-12)

The operator approved the connection and it became `AVAILABLE`.

```
aws codepipeline create-pipeline --cli-input-json file://pipeline.json
```

Creating a pipeline starts it. That first execution ran commit `65cd0dc`:

| Stage | Result | Time |
|-------|--------|------|
| Source | Succeeded | 4 s |
| Build | Succeeded | 2 min 3 s for the action; the build inside it ran 84 s |
| Deploy | **Failed** | 35 s |

```
Deployment failed. The provided role does not have sufficient permissions:
Service:Amazon S3, Message: ... cloudshop-pipeline-role ... is not authorized to perform:
s3:DeleteObject on resource: "arn:aws:s3:::elasticbeanstalk-us-east-1-<acct>/resources/environments/e-ymbnrtr5ck/_runtime/_versions/cloudshop/code-pipeline-..."
```

After a deploy, Elastic Beanstalk removes its staged copy of the bundle from its
own bucket, and it does that as the caller. The policy in 5.2 came from
CloudTrail's event history, which records bucket-level calls but not object-level
ones, so this one was not in it.

While that deploy was failed the environment showed Red: `Incorrect application
version found on all instances`. The site itself kept answering, on the previous
version, and scaling was not left suspended.

The fix is one statement, limited to this environment's folder:

```json
{
  "Sid": "RemoveTheEnvironmentsStagedCopyOfTheBundleAfterADeploy",
  "Effect": "Allow",
  "Action": "s3:DeleteObject",
  "Resource": "arn:aws:s3:::elasticbeanstalk-us-east-1-<acct>/resources/environments/e-ymbnrtr5ck/*"
}
```

`aws codepipeline retry-stage-execution --stage-name Deploy --retry-mode FAILED_ACTIONS`
then deployed the same build: Succeeded in 36 s, environment Green.

Elastic Beanstalk's events for a pipeline deploy read a little differently from
an `eb deploy`, because the bundle is a zip holding the jar rather than the jar
itself:

```
15:00:57  Deploying new version to instance(s).
15:01:03  Instance deployment successfully generated a 'Procfile'.
15:01:11  Instance deployment completed successfully.
15:01:18  Environment update completed successfully.
```

That generated `Procfile` is the platform's own, `web: java -jar cloudshop.jar`,
which is the point of sending the jar and nothing else.

The application version a pipeline run creates is named
`code-pipeline-<timestamp>-BuildArtifact-<id>`, and its source bundle is the
object in the artifact bucket, not a copy. **So the artifact of the version that
is live must not be deleted from the bucket**: a replacement instance would have
nothing to install. This is why the bucket has no expiry rule.

### 5.6 The test: a footer line, pushed

`src/main/resources/templates/fragments/layout.html`, line 92:

```html
<span class="float-end">Deployed by AWS CodePipeline</span>
```

Pushed as `c34e25b` at 15:02:34 UTC, together with the policy fix above.

**The push did not start the pipeline.** Seven minutes later there was still only
the one execution. Started by hand instead
(`aws codepipeline start-pipeline-execution --name cloudshop-pipeline`), the
pipeline ran that commit cleanly:

| Stage | Result | Time |
|-------|--------|------|
| Source | Succeeded | 3 s |
| Build | Succeeded | 1 min 40 s (the build inside it: 70 s, 151 tests) |
| Deploy | Succeeded | 36 s |
| **Whole run** | **Succeeded** | **2 min 20 s** |

And the footer is live through CloudFront, on `/`, `/products` and `/login`:

```html
<footer class="container border-top mt-5 py-3 text-muted small">
<span>CST-323 Cloud Computing &middot; CloudShop &middot; Spring Boot, Spring Security and MySQL</span>
<span class="float-end">Deployed by AWS CodePipeline</span>
</footer>
```

The Heroku site does not show it. Heroku still runs `3a99083`, and nothing pushes
there automatically; a later `git push heroku main` would carry the line with it,
where it would not be true.

### 5.7 Why the push did not start it

CloudTrail has the connection's whole history, and it is short:

```
14:52:37  StartOAuthHandshake
14:53:04  StartOAuthHandshake
14:53:23  GetIndividualAccessToken
14:53:35  UpdateConnectionOAuthToken
```

That is an authorization and nothing else. There is no installation step in it,
and the repository has no webhook. The connection can read the repository, which
is why the Source stage works, but GitHub has not been told to notify AWS of
pushes: that is done by the AWS Connector for GitHub app being installed on the
repository and tied to the connection. No `StartPipelineExecution` by anything
but the operator's own user appears after the push.

The pipeline's trigger itself is stored as intended:

```json
[{"providerType": "CodeStarSourceConnection",
  "gitConfiguration": {"sourceActionName": "GitHub",
                       "push": [{"branches": {"includes": ["main"]}}]}}]
```

So the remaining work is on the connection, and needs the account holder at the
console once more.
