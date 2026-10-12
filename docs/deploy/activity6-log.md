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
| 1. Logging with an interceptor | Done. 144 tests pass; committed and pushed |
| 2. AWS redeploy | Not started |
| 3. Heroku and Loggly | Not started. Blocked: no Heroku CLI on this machine |

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
