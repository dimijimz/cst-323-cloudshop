# AWS deployment log - Elastic Beanstalk + RDS for MySQL + CloudFront

Running log of deploying `cloudshop` to AWS Elastic Beanstalk (Java SE, Corretto 17
on Amazon Linux 2023) with an Amazon RDS for MySQL 8.4 database and a CloudFront
distribution in front for HTTPS, using the AWS CLI and the EB CLI. Entries are
appended as each step runs.

**Status:** deployed on 2026-10-05 and verified as far as can be done without
signing in. The signed-in checks (section 7.4) are waiting for the operator to run
the smoke test.

| | |
|---|---|
| Public URL (HTTPS) | <https://d2m8eu1qwn32t4.cloudfront.net> |
| Origin (HTTP only) | <http://cloudshop-env.eba-2eyx4ux7.us-east-1.elasticbeanstalk.com> |
| Database | RDS for MySQL 8.4.9, `cloudshop-db`, private |
| Deployed version | `cloudshop-1.0.0-0c20f11` |
| Migrations | V1 and V2 applied by MySQL unchanged |
| Running cost | about $26.60 a month, from Free plan credits (section 9) |

## Ground rules for this deployment

- Application code does not change to fit AWS. Everything platform-specific is
  configuration. The one exception: if real MySQL rejects a Flyway migration, that
  is a genuine bug and the migration gets fixed.
- Minimal cost: `us-east-1`, single-instance EB environment (no load balancer),
  `t3.micro`, a micro RDS instance, Single-AZ, 20 GB gp3, RDS Extended Support
  disabled, database not publicly accessible.
- No root access keys. The CLI signs in as a non-root IAM user.
- `DB_PASSWORD` and `OWNER_PASSWORD` are generated locally and never written to this
  log or to the repository. Wherever a command needs one, this log shows `<redacted>`.
- The artifact deployed is `target/cloudshop.jar`, not the repository
  (`deploy.artifact` in `.elasticbeanstalk/config.yml`).
- The AWS account id is masked in this log as `<acct>`, because the log is committed
  to a public repository.

## 1. Preflight - 2026-10-05

Workstation: Windows 11, Git Bash, JDK 21.0.9 on the PATH (the POM compiles to
release 17), AWS CLI 2.37.9, EB CLI 3.27.3 in `%USERPROFILE%\.ebcli-venv`.

The AWS CLI is installed at `C:\Program Files\Amazon\AWSCLIV2\aws.exe`. A shell that
was already open when it was installed does not have it on the PATH; calling it by
its full path works.

### 1.1 Identity

```bash
aws sts get-caller-identity
# Arn: arn:aws:iam::<acct>:user/cst323-admin
aws configure list
# access_key / secret_key: type "login"   region: us-east-1 (from ~/.aws/config)
```

- The caller is the IAM user `cst323-admin`, not root.
- The session comes from `aws login`, which caches short-lived credentials. There is
  no `~/.aws/credentials` file and the user has no access keys at all
  (`aws iam list-access-keys --user-name cst323-admin` returns none), so there is no
  long-lived key on this machine to leak.
- `cst323-admin` has the `AdministratorAccess` managed policy.

### 1.2 Build

```bash
git fetch origin                # main at 0c20f11, same as origin/main, working tree clean
./mvnw -B clean package         # BUILD SUCCESS, 99 tests, 0 failures
ls -la target/cloudshop.jar     # 66,096,829 bytes
```

The jar has no `Procfile`, `.ebextensions` or `.platform` at its top level, so
Elastic Beanstalk will run it with its default `java -jar` command and proxy to
port 5000.

### 1.3 Migration review for MySQL 8.4

`V1__create_schema.sql` and `V2__seed_data.sql` had only ever been executed by H2 in
MySQL compatibility mode. Reviewed statement by statement against MySQL 8.4 before
deploying. No changes were needed.

| Check | Result |
|-------|--------|
| Statements | V1: three `CREATE TABLE` and one `CREATE INDEX`. V2: one multi-row `INSERT`. |
| Identifiers | None is a reserved word in 8.4. `role`, `active`, `name` and `description` are keywords but non-reserved, so they need no quoting. |
| Column types | `BIGINT`, `VARCHAR(n)`, `INT`, `DECIMAL(p,s)`, `DATETIME(6)`, `BOOLEAN` (stored as `TINYINT(1)`). All native. No `ENUM`, no `TEXT`. |
| CHECK constraints | Enforced since 8.0.16. Six of them, each with a distinct name, which MySQL requires to be unique across the schema. None sits on a foreign key column. |
| Foreign keys | Both reference a primary key of the same type (`BIGINT`). This satisfies 8.4's new default `restrict_fk_on_non_standard_key=ON`, which rejects foreign keys that point at a non-unique or partial key. |
| Unique keys | `username` (50 chars) and `email` (120 chars) in utf8mb4 are 200 and 480 bytes, far below InnoDB's 3072-byte index limit. |
| Table options | `ENGINE = InnoDB DEFAULT CHARSET = utf8mb4`. The default collation is `utf8mb4_0900_ai_ci`, so the unique keys are case-insensitive. The application already compares usernames and emails without regard to case. |
| Comments | Every `--` is followed by whitespace, as MySQL requires. One inline comment contains a semicolon; it is inside a comment, so neither Flyway's parser nor MySQL treats it as a delimiter. |
| Special characters | Outside comments there is no `#`, backslash, backtick, double quote, `/*` or `DELIMITER`, which are the things Flyway's MySQL parser treats differently from its H2 parser. Single quotes are balanced. The files are pure ASCII. |
| Seed literals | `TRUE` and plain decimal literals. |

Libraries inside the jar that have to work with 8.4:

| Library | Version | Note |
|---------|---------|------|
| MySQL Connector/J | 9.5.0 | Supports 8.4 and its default `caching_sha2_password` authentication. `DB_SSL_MODE=REQUIRED` means the password exchange happens over TLS. |
| Flyway (core and mysql) | 10.20.1 | May log "Flyway upgrade recommended" for a server newer than it was tested with. That is a warning, not a failure. |
| Hibernate | 6.6.39 | Runs in `validate` mode. `boolean` matches `TINYINT(1)`; the `role` enum is pinned to `VARCHAR` in the entity, so Hibernate does not expect a native `ENUM`. |

What a review cannot prove: that the server accepts the files. The first start on
RDS is still the first time MySQL parses them. MySQL DDL is not transactional, so
if a statement in V1 failed, the tables created before it would remain and Flyway
would refuse to continue until they were removed.

### 1.4 What already exists in the account

Read-only inventory, all in `us-east-1`:

| Resource | Found |
|----------|-------|
| AWS Budgets | none, so `cst323-monthly` has to be created |
| Elastic Beanstalk applications and environments | none |
| RDS instances | none |
| CloudFront distributions | none |
| EC2 instances, Elastic IPs, key pairs | none |
| S3 buckets | none |
| Default VPC | present, with a public subnet in each of six Availability Zones |
| Security groups | only the VPC's `default` group |
| IAM roles for Elastic Beanstalk | neither `aws-elasticbeanstalk-ec2-role` nor `aws-elasticbeanstalk-service-role` exists |

Nothing is running, so nothing is using credits yet.

### 1.5 Findings that change the plan

1. **`db.t4g.micro` cannot be ordered in `us-east-1`.**
   `aws rds describe-orderable-db-instance-options --db-instance-class db.t4g.micro`
   returns nothing there for MySQL, MariaDB or PostgreSQL. It is orderable in
   `us-east-2`. For MySQL 8.4 in `us-east-1` the smallest classes on offer are
   `db.t3.micro` ($0.017 per hour) and `db.t4g.small` ($0.032 per hour);
   `db.t4g.micro` would have been $0.016 per hour. The plan asks for both
   `us-east-1` and `db.t4g.micro`, so one of the two has to give. Put to the
   operator; the answer is in section 1.7.
2. **The account is on the AWS Free plan.** Created 2026-09-27, with $100 in
   credits and a plan end date of 2027-03-27. Nothing is charged to a card; usage
   is drawn from the credits. The older 12-month free tier (750 hours of EC2 and
   RDS a month) does not apply to accounts of this kind, so the instances consume
   credits from the first hour.
3. **A budget that counts credits would never alert.** By default a cost budget
   reports spend after credits, which stays at $0 for as long as the credits last.
   `cst323-monthly` will therefore be created with credits excluded, so that it
   tracks what the resources actually cost.
4. **`t3.micro` is not offered in `us-east-1e`**, and the default VPC has a subnet
   there. The environment will be pinned to the `us-east-1a` subnet, and the
   database to the same zone, which also means no cross-zone data transfer between
   the two.
5. **Elastic Beanstalk's two IAM roles do not exist yet.** `eb create` creates
   them on first use: an instance profile for the EC2 instance and a service role
   for Elastic Beanstalk itself, both with AWS-managed policies.
6. **There is no EC2 key pair**, so `eb ssh` is not available unless one is
   created. It is only needed if a migration fails and the database has to be
   cleaned up by hand.
7. The Elastic Beanstalk platform is `64bit Amazon Linux 2023 v4.12.9 running
   Corretto 17`. The RDS default minor version for the 8.4 family is `8.4.9`.

### 1.6 Cost estimate

On-demand prices for `us-east-1`, read from the AWS Price List API on 2026-10-05
(the public IPv4 rate is AWS's published one). A month is taken as 730 hours.

| Item | Rate | Per month |
|------|------|-----------|
| EC2 `t3.micro` (the EB instance) | $0.0104 per hour | $7.59 |
| EBS root volume, 8 GB gp3 | $0.08 per GB-month | $0.64 |
| Public IPv4 address (EB's Elastic IP) | $0.005 per hour | $3.65 |
| RDS `db.t3.micro`, MySQL, Single-AZ | $0.017 per hour | $12.41 |
| RDS storage, 20 GB gp3 | $0.115 per GB-month | $2.30 |
| RDS automated backups | free up to the size of the database | $0.00 |
| CloudFront | covered by the always-free 1 TB and 10 million requests | $0.00 |
| S3 (application versions, about 66 MB each) | $0.023 per GB-month | under $0.01 |
| **Total** | about $0.036 per hour | **about $26.60** |

That is roughly $0.87 a day. Against $100 of credits it lasts about 115 days if
everything runs around the clock and nothing else uses credits. A $5 monthly budget
will reach its limit around the sixth day of each month; it only sends email and
does not stop anything.

### 1.7 Decisions from the operator

Asked before anything was created, answered 2026-10-05:

- **Database class:** `db.t3.micro` in `us-east-1`. Keeps the region in the plan at a
  cost of $0.73 a month over `db.t4g.micro`.
- **Go-ahead:** create everything, including CloudFront, then commit the log and the
  deploy configuration and push.

## 2. Budget - 2026-10-05

```bash
aws budgets describe-budget --account-id <acct> --budget-name cst323-monthly
# NotFoundException, so it is created:
aws budgets create-budget --account-id <acct> \
    --budget file://budget.json \
    --notifications-with-subscribers file://notifications.json
```

`budget.json`:

```json
{
  "BudgetName": "cst323-monthly",
  "BudgetLimit": { "Amount": "5", "Unit": "USD" },
  "TimeUnit": "MONTHLY",
  "BudgetType": "COST",
  "CostTypes": {
    "IncludeTax": true, "IncludeSubscription": true, "UseBlended": false,
    "IncludeRefund": false, "IncludeCredit": false, "IncludeUpfront": true,
    "IncludeRecurring": true, "IncludeOtherSubscription": true,
    "IncludeSupport": true, "IncludeDiscount": true, "UseAmortized": false
  }
}
```

`notifications.json` holds two alerts, both emailed to the operator's address (left
out of this log because the log is public): actual spend above 80% of the limit, and
actual spend above 100%.

- `"IncludeCredit": false` is the important line. The account pays for everything
  out of Free plan credits, so a budget that counted credits would sit at $0 and
  never send anything. This one measures what the resources cost before credits.
- The budget only notifies. It does not stop or delete anything.
- AWS Budgets itself is free for a budget without actions.

Verified with `describe-budget`, `describe-notifications-for-budget` and
`describe-subscribers-for-notification`: limit 5.0 USD, monthly, two notifications
in state `OK`, one email subscriber each.

## 3. RDS for MySQL - 2026-10-05

### 3.1 Passwords

Two passwords were generated locally with Python's `secrets` module, letters and
digits only so that no shell, URL or RDS quoting rule can mangle them: 32 characters
for `DB_PASSWORD` and 24 for `OWNER_PASSWORD`. They were written straight to

```
%USERPROFILE%\.cloudshop\aws-secrets.env
```

which is outside the repository and outside OneDrive. They have not been displayed
anywhere, and every command below reads them from that file with a shell
substitution rather than containing them.

### 3.2 Security group for the database

```bash
aws ec2 create-security-group --group-name cloudshop-db-sg \
    --description "CloudShop RDS - MySQL 3306 from the Elastic Beanstalk instance only" \
    --vpc-id vpc-0ed43bdc8b39aa7a5
# sg-029969900f8dca845, created with no inbound rules at all
```

The database is created behind this group before any rule is added to it, so there
is no moment at which it accepts a connection from anywhere.

### 3.3 The instance

```bash
aws rds create-db-instance \
    --db-instance-identifier cloudshop-db \
    --engine mysql --engine-version 8.4.9 \
    --db-instance-class db.t3.micro \
    --allocated-storage 20 --storage-type gp3 \
    --master-username dbadmin --master-user-password <redacted> \
    --db-name cloudshop \
    --availability-zone us-east-1a --no-multi-az \
    --no-publicly-accessible \
    --vpc-security-group-ids sg-029969900f8dca845 \
    --engine-lifecycle-support open-source-rds-extended-support-disabled \
    --storage-encrypted \
    --backup-retention-period 1 \
    --no-deletion-protection \
    --tags Key=project,Value=cloudshop
```

| Setting | Value | Why |
|---------|-------|-----|
| Engine | MySQL 8.4.9 | The RDS default minor version of the 8.4 LTS family. |
| Class | `db.t3.micro` | Operator's choice; `db.t4g.micro` cannot be ordered in this region. |
| Storage | 20 GB gp3, no autoscaling | The minimum. gp3's baseline 3000 IOPS and 125 MB/s are included in the price. |
| Multi-AZ | no | Single-AZ, in `us-east-1a` with the application. |
| Publicly accessible | no | Reachable only from inside the VPC. |
| Extended Support | disabled | The instance cannot drift into per-vCPU Extended Support charges; it has to be upgraded before 8.4 leaves standard support instead. |
| Encryption at rest | on | Free with the AWS-managed `aws/rds` key. Not in the plan; added because it costs nothing. |
| Automated backups | 1 day | Free up to the size of the database. |
| Deletion protection | off | So that tearing the project down is one command. |
| Enhanced Monitoring, Performance Insights, log exports | all off | Each of them can cost money. |

Creation started at 03:05 local time (07:05 UTC).

## 4. Elastic Beanstalk - 2026-10-05

### 4.1 Application and CLI configuration

```bash
eb init cloudshop --platform "64bit Amazon Linux 2023 v4.12.9 running Corretto 17" --region us-east-1
# Application cloudshop has been created.
```

The EB CLI uses the same `aws login` session as the AWS CLI; no keys were entered.
`eb init` wrote `.elasticbeanstalk/config.yml`, and this was added to it by hand so
that `eb deploy` uploads the jar instead of zipping the repository:

```yaml
deploy:
  artifact: target/cloudshop.jar
```

### 4.2 Environment

```bash
eb create cloudshop-env --single --instance_type t3.micro --sample \
    --platform "64bit Amazon Linux 2023 v4.12.9 running Corretto 17" \
    --vpc.id vpc-0ed43bdc8b39aa7a5 --vpc.ec2subnets subnet-05aa00b5b8cd6c379 --vpc.publicip \
    --tags project=cloudshop --timeout 25
```

- `--single` means one EC2 instance with an Elastic IP and no load balancer.
- `--sample` starts the environment on AWS's sample application. CloudShop refuses
  to start without `DB_PASSWORD`, and the database endpoint did not exist yet, so
  deploying the real jar at this point would only have produced a red environment.
  The jar goes on in step 7, after the variables are set.
- The `--vpc.*` options pin the instance to the `us-east-1a` subnet (finding 4 in
  section 1.5).

```
07:06:11 UTC  createEnvironment is starting.
07:06:12      Using elasticbeanstalk-us-east-1-<acct> as Amazon S3 storage bucket for environment data.
07:06:39      Created security group named: sg-0e429cd4c3327bf88
07:06:54      Created EIP: 100.56.134.187
07:07:28      Waiting for EC2 instances to launch. This may take a few minutes.
07:08:43      Instance deployment completed successfully.
07:09:16      Application available at cloudshop-env.eba-2eyx4ux7.us-east-1.elasticbeanstalk.com.
07:09:17      Successfully launched environment: cloudshop-env
```

Three minutes, health Green. What it created, checked afterwards:

| Resource | Detail |
|----------|--------|
| EC2 instance | `t3.micro` in `us-east-1a`, IMDSv2 required, no key pair, 8 GB gp3 root volume |
| Elastic IP | `100.56.134.187` |
| Security group | `sg-0e429cd4c3327bf88`, one inbound rule: TCP 80 from anywhere. No SSH rule. |
| S3 bucket | `elasticbeanstalk-us-east-1-<acct>`, for application versions and logs |
| IAM instance profile | `aws-elasticbeanstalk-ec2-role` with the AWS-managed policies `AWSElasticBeanstalkWebTier`, `AWSElasticBeanstalkWorkerTier` and `AWSElasticBeanstalkMulticontainerDocker` |
| IAM service role | `aws-elasticbeanstalk-service-role` with `AWSElasticBeanstalkEnhancedHealth` and `AWSElasticBeanstalkManagedUpdatesCustomerRolePolicy` |
| Load balancer | none |

The two IAM roles are the EB CLI's standard defaults. It creates them the first time
an account uses Elastic Beanstalk.

## 5. Database security group rule - 2026-10-05

```bash
aws ec2 authorize-security-group-ingress --group-id sg-029969900f8dca845 \
    --ip-permissions "IpProtocol=tcp,FromPort=3306,ToPort=3306,UserIdGroupPairs=[{GroupId=sg-0e429cd4c3327bf88}]"
# sgr-0c20b5b8d40a42187
```

`cloudshop-db-sg` now has exactly one inbound rule: TCP 3306 from the Elastic
Beanstalk instance security group. There is no CIDR rule, so no IP address is
allowed in, only instances that carry that group. The database has no other
security group attached and is not publicly accessible.

One consequence to remember at teardown: while this rule exists, the Elastic
Beanstalk security group has something depending on it, and terminating the
environment will hang on deleting that group. Remove this rule, or delete the
database and `cloudshop-db-sg`, before `eb terminate` (see the teardown section).

The database reported `available` at 03:12 local time, about seven minutes after
`create-db-instance`.

## 6. Environment variables - 2026-10-05

```bash
eb setenv PORT=5000 \
    DB_HOST=cloudshop-db.<id>.us-east-1.rds.amazonaws.com DB_PORT=3306 DB_NAME=cloudshop \
    DB_USER=dbadmin DB_PASSWORD=<redacted> DB_SSL_MODE=REQUIRED \
    OWNER_USERNAME=owner OWNER_PASSWORD=<redacted> \
    ACTUATOR_HEALTH_DETAILS=never
```

```
07:12:21 UTC  Environment update is starting.
07:12:28      Updating environment cloudshop-env's configuration settings.
07:13:05      Instance deployment completed successfully.
07:13:40      Successfully deployed new configuration to environment.
```

- `PORT=5000` is the port Elastic Beanstalk's nginx proxies to on the Java SE
  platform. The application reads it through `server.port=${PORT:8080}`.
- `DB_SSL_MODE=REQUIRED` makes Connector/J refuse an unencrypted connection.
- `ACTUATOR_HEALTH_DETAILS=never` is set as the plan asks, but CloudShop does not
  read it. Unlike employee-manager, CloudShop fixes `show-details=never` in
  `application.properties`, so the variable is harmless and has no effect.
- The two passwords were passed from the secrets file by shell substitution, and the
  command's output was filtered for both values as a safety net. `eb setenv` prints
  only events, never values.
- Checked afterwards by listing the variable **names** on the environment; the values
  were deliberately not fetched.

Anyone who can open the Elastic Beanstalk console for this environment, or run
`eb printenv`, can read these values in plain text. That is how environment
properties work on Elastic Beanstalk, and it is one more reason the account should
stay limited to people who are meant to have the owner password.

## 7. Deploy and verify - 2026-10-05

### 7.1 Deploy

```bash
eb deploy cloudshop-env --label cloudshop-1.0.0-0c20f11 \
    --message "CloudShop 1.0.0 jar built from 0c20f11"
```

The artifact is `target/cloudshop.jar` from the preflight build: 66,096,829 bytes,
SHA-256 beginning `bda64bf9536178e6`, built from commit `0c20f11` with no source
changes since.

```
07:14:01 UTC  Environment update is starting.
07:14:07      Deploying new version to instance(s).
07:14:10      Instance deployment successfully detected a JAR file in your source bundle.
07:14:11      Instance deployment successfully generated a 'Procfile'.
07:14:18      Instance deployment completed successfully.
07:14:24      New application version was deployed to running EC2 instances.
07:14:24      Environment update completed successfully.
```

Environment afterwards: status `Ready`, health `Green` / `Ok`, version
`cloudshop-1.0.0-0c20f11`.

### 7.2 Flyway on real MySQL

This was the first time MySQL itself parsed the migrations. From `eb logs`
(`/var/log/web.stdout.log`), endpoint id masked:

```
07:14:18.952 INFO  CloudShopApplication - Starting CloudShopApplication v1.0.0 using Java 17.0.20.1 with PID 3679 (/var/app/current/application.jar started by webapp in /var/app/current)
07:14:25.096 INFO  FlywayExecutor - Database: jdbc:mysql://cloudshop-db.<id>.us-east-1.rds.amazonaws.com:3306/cloudshop?sslMode=REQUIRED&...
07:14:25.159 WARN  Database - Flyway upgrade recommended: MySQL 8.4 is newer than this version of Flyway and support has not been tested. The latest supported version of MySQL is 8.1.
07:14:25.261 INFO  DbValidate - Successfully validated 2 migrations (execution time 00:00.071s)
07:14:25.451 INFO  DbMigrate - Current version of schema `cloudshop`: << Empty Schema >>
07:14:25.489 INFO  DbMigrate - Migrating schema `cloudshop` to version "1 - create schema"
07:14:25.707 INFO  DbMigrate - Migrating schema `cloudshop` to version "2 - seed data"
07:14:25.748 INFO  DbMigrate - Successfully applied 2 migrations to schema `cloudshop`, now at version v2 (execution time 00:00.170s)
07:14:32.145 INFO  CloudShopApplication - Started CloudShopApplication in 14.969 seconds (process running for 16.072)
07:14:32.618 INFO  UserService - CREATE user: id=1 username=owner role=OWNER
07:14:32.632 INFO  OwnerAccountInitializer - Created the owner account 'owner' from OWNER_USERNAME / OWNER_PASSWORD
07:14:32.635 INFO  CloudShopApplication - CloudShop started on port 5000 against datasource jdbc:mysql://cloudshop-db.<id>.us-east-1.rds.amazonaws.com:3306/cloudshop?sslMode=REQUIRED&...
```

- **V1 and V2 both applied on the first attempt.** No migration needed fixing.
- The Flyway line at WARN is the one predicted in section 1.3. It is advice, not an
  error; the migrations ran.
- The application reaching "Started" also means Hibernate's `validate` accepted the
  schema MySQL built: `BOOLEAN` as `TINYINT(1)`, the `role` column as `VARCHAR`, and
  `DATETIME(6)` all matched the entity mappings.
- The owner account was created from the two environment variables.
- The retrieved log was searched for both password values. Neither appears.

### 7.3 HTTP checks that need no sign-in

Against `http://cloudshop-env.eba-2eyx4ux7.us-east-1.elasticbeanstalk.com`:

| Request | Result |
|---------|--------|
| `GET /health` | 200, `{"status":"UP","application":"cloudshop",...}` |
| `GET /actuator/health` | 200, `{"status":"UP","groups":["liveness","readiness"]}`. UP means the database check passed; no component details are shown. |
| `GET /actuator/info` | 200, application name and course |
| `GET /`, `/products`, `/products/1`, `/login`, `/register` | 200 |
| `GET /products` content | All eight seeded products, read from MySQL, with the seed prices and stock: Webcam 1, Monitor 8, Laptop Stand 0 with the "Out of stock" badge, Keyboard 25, Headphones 5, SSD 30, Dock 12, Mouse 40 |
| `GET /products/999` | 404 |
| `GET /owner/products`, `/owner/sales`, `/my-purchases` | 302 to `/login` |
| `GET /actuator/env`, `/h2-console` | 302 to `/login`; neither is exposed |
| `GET /login` | The form posts to `/login`, carries a CSRF token, and the response sets an `HttpOnly` session cookie |
| `POST /products/1/purchase` with no token | 403, and the stock of product 1 is still 25 |
| `POST /owner/products/1/deactivate` with no token | 403 |
| `POST /register` with no token | 403 |
| Response headers | `Server: nginx`, `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, `Cache-Control: no-cache, no-store` |

### 7.4 Signed-in checks: for the operator to run

The plan's remaining tests all require an account: register a buyer, buy something,
confirm the stock dropped, sign in as the owner, change a price, check the sales
history. They were **not** run as part of this deployment. The assistant carrying
out the deployment does not create accounts on, or sign in to, a site that is not
running on the local machine, even the operator's own. Those steps were packaged
instead as a script for the operator to run:

```bash
./docs/deploy/smoke-test.sh https://d2m8eu1qwn32t4.cloudfront.net
```

It drives the real forms with curl, CSRF tokens and session cookies included, and
reads the owner's password from the secrets file without printing it. It was run
against a local instance (H2 profile) before being handed over and passed all 38 of
its checks there; it was also run with a wrong owner password and with no
credentials to confirm that it fails loudly when it should.

It leaves one buyer account (`smoke_<timestamp>`) and one purchase behind, so one
product ends up with one unit less. It raises a price by a cent and puts it back.

Until that script, or the same steps by hand in a browser, has been run against the
deployed site, the purchase transaction and the sales query have not been exercised
on MySQL. Everything they depend on has been: the schema, the connection, reads of
the catalog, and an insert (the owner account).

## 8. CloudFront for HTTPS - 2026-10-05

A single-instance Elastic Beanstalk environment has no load balancer to hold a
certificate, so it only speaks HTTP. CloudFront in front of it provides HTTPS on a
`cloudfront.net` name with Amazon's own certificate, at no fixed cost.

### 8.1 Distribution

```bash
aws cloudfront create-distribution-with-tags --distribution-config-with-tags file://cloudfront.json
# Id E39LB8FY296PK2, domain d2m8eu1qwn32t4.cloudfront.net
aws cloudfront wait distribution-deployed --id E39LB8FY296PK2      # about 3.5 minutes
```

The parts of `cloudfront.json` that matter:

```json
{
  "Origins": { "Items": [ {
      "Id": "cloudshop-env",
      "DomainName": "cloudshop-env.eba-2eyx4ux7.us-east-1.elasticbeanstalk.com",
      "CustomOriginConfig": { "HTTPPort": 80, "OriginProtocolPolicy": "http-only" }
  } ] },
  "DefaultCacheBehavior": {
    "ViewerProtocolPolicy": "redirect-to-https",
    "AllowedMethods": { "Items": ["GET", "HEAD", "OPTIONS", "PUT", "POST", "PATCH", "DELETE"] },
    "CachePolicyId": "4135ea2d-6df8-44a3-9df3-4b5a84be39ad",
    "OriginRequestPolicyId": "216adef6-5c7f-47e4-b989-5492eafa07d3"
  },
  "ViewerCertificate": { "CloudFrontDefaultCertificate": true },
  "PriceClass": "PriceClass_100"
}
```

| Setting | Value | Why |
|---------|-------|-----|
| Viewer protocol | redirect HTTP to HTTPS | Nobody stays on plain HTTP through this name. |
| Certificate | CloudFront default, `*.cloudfront.net` | No domain or ACM certificate needed. |
| Cache policy | AWS-managed `CachingDisabled` | Every page is per-user and depends on the session; a cached page could show one user another's data. |
| Origin request policy | AWS-managed `AllViewer` | Forwards every cookie, header and query string. The session cookie and the form's CSRF token have to reach the application, and so does the `Host` header, so that the application's redirects point back at CloudFront and not at the Elastic Beanstalk name. |
| Allowed methods | all seven | Forms are submitted with POST. |
| Origin protocol | HTTP only | The environment has nothing listening on 443. |
| Price class | 100 (North America and Europe) | The cheapest set of edge locations. |
| WAF, logging, aliases | none | Each is either a cost or needs a domain. |

### 8.2 Checks through CloudFront that need no sign-in

Against `https://d2m8eu1qwn32t4.cloudfront.net`:

| Check | Result |
|-------|--------|
| TLS | TLS 1.3, certificate `CN=*.cloudfront.net` issued by Amazon RSA 2048 M04, verified by both curl and openssl |
| `GET http://.../products` | 301 to `https://.../products` |
| `GET /health`, `/actuator/health`, `/`, `/products`, `/products/1`, `/login`, `/register` | 200 |
| `GET /products` content | all eight products |
| `GET /products/999` | 404 |
| `GET /owner/sales`, `/my-purchases`, `/actuator/env` | 302 to `http://d2m8eu1qwn32t4.cloudfront.net/login`, which CloudFront answers with 301 to the HTTPS form. The redirect names CloudFront, not Elastic Beanstalk, which shows the `Host` header is forwarded. |
| Caching | Two requests for `/health` a second apart: both `X-Cache: Miss from cloudfront`, and each has its own timestamp |
| Cookies | The first `GET /login` sets a session cookie. A second one sent with that cookie gets no new cookie, so the cookie reached the application and the session was recognised. |
| Query strings | `/login?registered` shows the "Account created" banner and `/login?error` shows "Invalid username or password"; `/login` alone shows neither |
| `POST /products/1/purchase` and `POST /register` with no token | 403 from the application, so POST passes through CloudFront and CSRF protection is working behind it |

Signing in through CloudFront is the one thing left for the operator, with the
script in section 7.4.

### 8.3 What CloudFront does not fix

- **HTTPS ends at CloudFront.** From CloudFront to the instance the request is plain
  HTTP, so passwords and session cookies travel that last leg unencrypted. Closing
  that gap needs a certificate on the origin, which in turn needs a domain name.
- **The Elastic Beanstalk URL still answers on its own**, over HTTP, to anyone. Use
  the CloudFront URL for anything that involves signing in. The environment's
  security group could be narrowed to CloudFront's address ranges; that was not part
  of this plan and has not been done.
- Because the application sees HTTP, its session cookie is not marked `Secure`, and
  the redirect to the sign-in page is issued as `http://` and costs one extra hop
  before CloudFront upgrades it.
- With the default certificate, CloudFront's minimum TLS version cannot be raised
  above its default policy. Modern browsers negotiate TLS 1.3 regardless.

## 9. What is running, what it costs, and how to remove it

### 9.1 Running now

| Resource | Identifier | Cost |
|----------|------------|------|
| Elastic Beanstalk environment | `cloudshop-env` in application `cloudshop` | nothing itself |
| EC2 instance | `t3.micro`, `us-east-1a` | $0.0104 per hour |
| EBS root volume | 8 GB gp3 | $0.64 per month |
| Elastic IP | `100.56.134.187` | $0.005 per hour |
| RDS instance | `cloudshop-db`, `db.t3.micro`, MySQL 8.4.9 | $0.017 per hour |
| RDS storage | 20 GB gp3 | $2.30 per month |
| CloudFront distribution | `E39LB8FY296PK2` | $0 within the always-free allowance |
| S3 bucket | `elasticbeanstalk-us-east-1-<acct>` | under one cent a month |
| Budget, IAM roles, security groups, EB application | | free |

About $0.036 an hour, $0.87 a day, $26.60 a month, taken from the account's Free plan
credits. Nothing is charged to a card while the account is on that plan.

A final inventory after the deployment found exactly the resources above and
nothing else that bills: no load balancer, NAT gateway, WAF web ACL or Secrets
Manager secret, and one automated RDS snapshot, which is free at this size.

The same check showed **$140 of credits remaining, up from $100 at preflight.** AWS
gives Free plan accounts extra credits for certain first-time activities, and
creating the budget, the instance and the database appear to have counted; that
reason is an inference, the balance is what the API reported. At $0.87 a day, $140
lasts about 160 days, which is close to the plan's own end date of 2027-03-27.

### 9.2 Tearing it down

In this order. The second step matters: the database's security group refers to the
environment's, and the environment cannot delete its group while that reference
exists.

```bash
# 1. CloudFront: a distribution must be disabled before it can be deleted.
aws cloudfront get-distribution-config --id E39LB8FY296PK2 --query DistributionConfig --output json \
    | sed 's/"Enabled": true/"Enabled": false/' > disabled.json
aws cloudfront update-distribution --id E39LB8FY296PK2 --distribution-config file://disabled.json \
    --if-match "$(aws cloudfront get-distribution-config --id E39LB8FY296PK2 --query ETag --output text)"
aws cloudfront wait distribution-deployed --id E39LB8FY296PK2
aws cloudfront delete-distribution --id E39LB8FY296PK2 \
    --if-match "$(aws cloudfront get-distribution-config --id E39LB8FY296PK2 --query ETag --output text)"

# 2. Remove the rule that ties the two security groups together.
aws ec2 revoke-security-group-ingress --group-id sg-029969900f8dca845 \
    --security-group-rule-ids sgr-0c20b5b8d40a42187

# 3. Elastic Beanstalk: the instance, its Elastic IP and its security group go with it.
eb terminate cloudshop-env --force

# 4. The database, with no final snapshot, then its security group.
aws rds delete-db-instance --db-instance-identifier cloudshop-db \
    --skip-final-snapshot --delete-automated-backups
aws rds wait db-instance-deleted --db-instance-identifier cloudshop-db
aws ec2 delete-security-group --group-id sg-029969900f8dca845
```

These commands have not been run; they are written from the resources above. Step 4
deletes every buyer and purchase for good.

Left behind, all free or nearly so: the empty `cloudshop` application
(`eb terminate --all` removes it and its stored versions), the S3 bucket, the two
IAM roles, the default DB subnet group and the budget. Delete
`%USERPROFILE%\.cloudshop\aws-secrets.env` once the database and environment are gone.

To stop paying for the database without losing its data, `aws rds stop-db-instance
--db-instance-identifier cloudshop-db` stops the hourly charge for up to seven days,
after which RDS starts it again by itself. Storage is still billed while it is
stopped, and the site is down.
