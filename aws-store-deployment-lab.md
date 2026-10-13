# Deploy Your Store: S3, Route 53, PostgreSQL, Kafka and Beanstalk

## Starting point: infrastructure is already provisioned

**Your instructor has already provisioned the Lightsail Linux instance and the Lightsail managed PostgreSQL database in AWS.** You will create your own logical database, Kafka topics, DNS records and application deployment. Do not create another shared server, replace the broker, or delete another student's resources.

This is a guided deployment lab, not a completed configuration. Examples use `roflgator`. Replace that token with your assigned lowercase name everywhere it identifies your resources. Fields marked `TODO` or `xxxx` must be completed before running the application.

The shared resources for this class are:

| Resource | Instructor-provided value |
| --- | --- |
| AWS Region | `us-east-1` |
| Route 53 public hosted zone | `livefree.click` |
| Linux static IPv4 address | `32.199.0.184` |
| Shared Linux hostname | `linux.livefree.click` |
| PostgreSQL endpoint | `ls-de1b46cf51b8647cb2b1b43ccbacbb06433f584c.citm64k00r0w.us-east-1.rds.amazonaws.com` |
| PostgreSQL port | `5432` |
| Database username | `dbmasteruser` |
| Database password | Supplied separately by your instructor |

Confirm these values with your instructor if the infrastructure has been replaced. Never put the database password in S3 or JavaScript.

### Instructor reference: Docker installation

These are the setup commands for an Ubuntu lab VM. **Students skip these if Docker is already installed.** Run them through Lightsail's browser SSH terminal, not in Windows PowerShell.

```bash
sudo apt-get update
sudo apt-get install -y curl
curl -fsSL https://get.docker.com -o get-docker.sh
sudo sh get-docker.sh
sudo systemctl enable --now docker
sudo docker run --rm hello-world
```

Docker's convenience installer is intended for development/testing. The VM runs Docker Engine; Docker Desktop is not required. [1]

### Instructor reference: PostgreSQL installation versus this lab's database

**There is no Docker installation command for the managed Lightsail database used in this lab. AWS installed and manages that PostgreSQL server.** Creating `roflgator-store` means creating a database inside that existing server.

For comparison only, this is how an instructor could start a separate PostgreSQL container on a disposable VM. It is **not the database behind the RDS endpoint above**, and students must not run it on the shared Kafka VM:

```bash
read -s -p 'Temporary PostgreSQL password: ' LAB_POSTGRES_PASSWORD
printf '\n'
sudo docker run -d --name postgres-demo --restart unless-stopped -p 127.0.0.1:5432:5432 -v postgres-demo-data:/var/lib/postgresql/data -e POSTGRES_USER=dbmasteruser -e POSTGRES_PASSWORD="$LAB_POSTGRES_PASSWORD" -e POSTGRES_DB=roflgator-store postgres:17
unset LAB_POSTGRES_PASSWORD
```

This illustration binds PostgreSQL to the VM's loopback interface, uses a persistent volume and initializes the database only on first startup with an empty data directory. It does not configure TLS or remote access; the lab's `sslmode=require` connection is intended for managed Lightsail PostgreSQL. [2]

## Your deployment map

Complete the right-hand column before starting. Use a unique short lowercase name with letters and digits. Keep the hyphens and dots in the examples in their designated positions.

| Item | Example | Your value |
| --- | --- | --- |
| Student token | `roflgator` | __________ |
| S3 bucket and website hostname | `www-roflgator.livefree.click` | __________ |
| Logical PostgreSQL database | `roflgator-store` | __________ |
| Database DNS hostname | `roflgator-postgres.livefree.click` | __________ |
| Kafka DNS hostname | `roflgator-linux.livefree.click` | __________ |
| Validation topic | `roflgator-purchases.validate` | __________ |
| Processing topic | `roflgator-purchases.process` | __________ |
| Validator consumer group | `roflgator-validators` | __________ |
| Processor consumer group | `roflgator-processors` | __________ |
| Backend DNS hostname | `app-roflgator.livefree.click` | __________ |

The frontend will live in S3. Its JavaScript calls the Beanstalk API. The API reads/writes PostgreSQL and sends/consumes Kafka messages. Browsers do not connect directly to PostgreSQL or Kafka.

**Naming challenge:** Which strings are yours to rename, and which are framework property keys that must remain unchanged?

## 1. Publish the static frontend to S3

### 1.1 Inspect the project

Locate `src/main/resources/static` in the full Spring Boot project. Upload the **contents** of this directory to S3, preserving relative paths; do not place everything underneath a new `static/` directory.

The supplied pages reference `styles.css`, `common.js` and several navigation targets. The attachments are only a subset of the website. Collect all HTML, CSS, JavaScript, images and other assets from the full project. If using the timestamped attached home page, give the deployed file the normal name `index.html`.

Keep a local copy in the project initially so you can test the pages from Spring Boot later. Copying them to S3 is sufficient to migrate their hosting; deleting your local source files is unnecessary.

### 1.2 Create and configure the bucket

1. Create a general-purpose S3 bucket named `www-roflgator.livefree.click`, replacing `roflgator` first.
2. Use the instructor's Region, normally `us-east-1`.
3. Keep ACLs disabled; use a bucket policy for public reads.
4. Enable **Static website hosting** and set the index document to `index.html`.
5. Upload all static assets to the bucket root.
6. Adjust the bucket's Block Public Access settings to permit the intended public-read bucket policy. Account or organization restrictions can still prevent this; ask the instructor if a higher-level restriction blocks the lab.
7. Add this policy after replacing the example bucket name:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "PublicReadWebsiteObjects",
      "Effect": "Allow",
      "Principal": "*",
      "Action": "s3:GetObject",
      "Resource": "arn:aws:s3:::www-roflgator.livefree.click/*"
    }
  ]
}
```

This grants public object reads, not uploads or bucket administration. Upload only public website assets: no JAR, properties file, credentials or Java source.

Open the **website endpoint** copied from the bucket's Properties tab. Use `http://` for this stage. Direct S3 website endpoints do not support HTTPS. [3][4]

**Checkpoint:** The home page, stylesheet and navigation load. API requests may fail now because the backend is not connected yet.

## 2. Give the S3 website its own Route 53 name

In the existing public hosted zone `livefree.click`, create:

| Setting | Student task |
| --- | --- |
| Record name | `www-roflgator` with your token |
| Type | `A` |
| Alias | Enabled |
| Target | S3 website endpoint in your bucket's Region |
| Routing policy | Simple |
| Evaluate target health | No |

The complete hostname must **exactly match the bucket name**. `www-roflgator.livefree.click` and `www.roflgator.livefree.click` are different names. An ordinary A record takes an IP address; an AWS alias A record can target an eligible AWS service. [3]

If the bucket is missing from the alias selector, check the account, Region, website hosting and exact name first. Do not select an unrelated bucket.

### CNAME fallback for this subdomain

If you cannot select the correct alias target, use a **CNAME** at the same subdomain pointing to the bucket's exact website endpoint hostname copied from AWS. Remove `http://` and any trailing path. Do not use the S3 REST endpoint or an `s3://` address.

Remove the conflicting A record before creating the CNAME: a CNAME cannot coexist with other record types at the same name. This fallback works for this subdomain, not the hosted-zone apex `livefree.click`. It does not fix a wrong bucket name, permissions or disabled website hosting. [3][4]

Verify in Windows PowerShell:

```powershell
Resolve-DnsName www-roflgator.livefree.click
```

Open `http://www-roflgator.livefree.click` using your replacement name.

**Explain:** Why does a successful DNS lookup not guarantee that S3 will return `index.html`?

## 3. Create your database inside the existing PostgreSQL server

### 3.1 Connect to the shared server

Use pgAdmin, DBeaver or `psql` with the instructor-provided endpoint, port, username and password. Connect initially to the existing `postgres` database with SSL required. The Lightsail resource name, such as `thornton-db`, is not necessarily a SQL database name. [5]

If you need a command-line client on the Linux VM, ask the instructor or use:

```bash
sudo apt-get install -y postgresql-client
```

Then connect; the password is prompted separately:

```bash
psql "host=ls-de1b46cf51b8647cb2b1b43ccbacbb06433f584c.citm64k00r0w.us-east-1.rds.amazonaws.com port=5432 dbname=postgres user=dbmasteruser sslmode=require" -W
```

Create your logical database. Replace the token before executing:

```sql
CREATE DATABASE "roflgator-store";
```

The double quotes matter because the name contains a hyphen. Run this as a standalone statement with autocommit enabled, not inside a transaction block. If it already exists, inspect it rather than dropping it.

### 3.2 Create a database CNAME

Create `roflgator-postgres` in the hosted zone, replacing the token. Use type **CNAME**, targeting the full AWS PostgreSQL endpoint hostname above.

Do not include `jdbc:postgresql://`, a port, database name or query parameters in DNS. Do not copy the database's current resolved IP into an A record; its address can change.

Test your CNAME and connect through it to your new database:

```powershell
Resolve-DnsName roflgator-postgres.livefree.click
Test-NetConnection roflgator-postgres.livefree.click -Port 5432
```

Complete this connection template in the AWS profile later:

```properties
spring.datasource.url=jdbc:postgresql://roflgator-postgres.livefree.click:xxxx/xxxx?sslmode=require
```

**Checkpoint:** Through your CNAME, run `SELECT current_database();` and confirm your student database name. A successful port test alone does not prove password or database access.

The supplied lab uses public mode on the managed database. Confirm it is enabled if testing from outside Lightsail. Do not attempt to fix managed-database access by opening port 5432 on the separate Kafka VM.

`sslmode=require` encrypts the JDBC connection but does not verify the certificate identity/hostname. A production setup should use verified TLS; a custom CNAME may not match the managed database certificate. [6]

## 4. Name and verify the shared Kafka broker

### 4.1 Create your Kafka CNAME

Create `roflgator-linux` as a **CNAME** pointing to `linux.livefree.click`, replacing the student token. The instructor's shared `linux` **A record** should resolve to `32.199.0.184`.

A CNAME points to a hostname, never directly to `32.199.0.184`. If the shared `linux` record is absent, the instructor must create or repair it.

```powershell
Resolve-DnsName roflgator-linux.livefree.click
Test-NetConnection roflgator-linux.livefree.click -Port 9092
```

Kafka has two relevant addresses: the bootstrap address your application uses first, and the broker address returned in Kafka metadata. The shared broker may advertise `linux.livefree.click:9092`; that is expected. Both your student alias and the advertised shared hostname must resolve and be reachable. Students must not repeatedly recreate the shared broker to change its advertised hostname.

### 4.2 SSH into the existing Linux instance

Use **Lightsail -> Instances -> the assigned Linux instance -> Connect using SSH**. Check:

```bash
sudo docker ps --filter name=kafka
sudo docker logs --tail 30 kafka
```

If Kafka is absent or stopped, ask the instructor to restore it. Do not launch a second broker on port 9092.

The instructor must allow TCP 9092 from the public outbound addresses used by local development machines and Beanstalk. For Beanstalk this could be EC2 public IPs or a NAT gateway Elastic IP; it is not necessarily the IP returned by the application's public URL. Ports 19092 and 9093 remain internal. This broker uses unauthenticated plaintext, so limit the allowed sources.

## 5. Create your two Kafka topics and update the source code

Kafka calls these **topics**, even though the application uses queue-like processing. Create your own pair; do not use another student's topics.

Replace `roflgator` in each command, then run it in the Lightsail SSH terminal. Each command is one line. The small heap override avoids starting another 512 MB Java process on the shared VM.

```bash
sudo docker exec -e KAFKA_HEAP_OPTS="-Xms32m -Xmx128m" kafka /opt/kafka/bin/kafka-topics.sh --create --if-not-exists --topic roflgator-purchases.validate --bootstrap-server localhost:19092 --partitions 3 --replication-factor 1
```

```bash
sudo docker exec -e KAFKA_HEAP_OPTS="-Xms32m -Xmx128m" kafka /opt/kafka/bin/kafka-topics.sh --create --if-not-exists --topic roflgator-purchases.process --bootstrap-server localhost:19092 --partitions 3 --replication-factor 1
```

Verify:

```bash
sudo docker exec -e KAFKA_HEAP_OPTS="-Xms32m -Xmx128m" kafka /opt/kafka/bin/kafka-topics.sh --describe --topic roflgator-purchases.validate --bootstrap-server localhost:19092
```

```bash
sudo docker exec -e KAFKA_HEAP_OPTS="-Xms32m -Xmx128m" kafka /opt/kafka/bin/kafka-topics.sh --describe --topic roflgator-purchases.process --bootstrap-server localhost:19092
```

`--if-not-exists` does not correct an existing topic's partition count. Confirm three partitions and replication factor one in the output.

### Source-code investigation

Search the full project for `roflgator`, `kafka.send`, `@KafkaListener` and `FakeKafka.start`.

| File | What you must inspect and change |
| --- | --- |
| `StoreProducers.java` | The topic passed to `kafka.send` in `submit` |
| `StoreConsumers.java` | Both listener topic names and the forwarding `kafka.send` topic |
| `StoreConsumers.java` | Give `validators` and `processors` student-specific group IDs |
| `FakeKafka.java` | Topic names used by the embedded development broker |
| `application.properties` | Your AWS hostnames and database settings |

Use the exact topic names you created. Prefix consumer groups too, so each student's offsets and assignments are independent. Group IDs are not topics; you do not create them with `kafka-topics.sh`.

The supplied `FakeKafka.java` contains this deliberate configuration problem:

```java
broker.brokerListProperty("roflgator-spring.kafka.bootstrap-servers");
```

**Repair exercise:** This argument identifies a Spring configuration key, not a student-owned resource. Find the correct standard bootstrap property key in your properties file and use that exact key. Replacing `roflgator` with your own name here would still be wrong. [7]

### Prevent embedded Kafka from starting in AWS

The supplied `FakeKafka` is a plain class with a static `start()` method. It imports `Profile`, but it is not a Spring-managed configuration class. **Putting `@Profile("dev")` on this class does not stop a direct call to `FakeKafka.start()`.**

Find the call site in the full application; the main application class was not supplied with these lab excerpts. Refactor the embedded broker into a Spring-managed configuration:

1. Create a scanned `@Configuration` class with `@Profile("dev")`.
2. Expose the configured `EmbeddedKafkaKraftBroker` through an `@Bean` method.
3. Preserve the development topic names and correct bootstrap property mapping.
4. Let Spring manage the broker lifecycle. Remove the manual `afterPropertiesSet()` call and shutdown hook from this bean-based approach; the broker implements Spring lifecycle interfaces.
5. Remove the old unconditional static startup call. Ensure no other bean requires the embedded broker under `aws`.
6. Keep producer/consumer client factories using Boot's configured bootstrap property; remove any hard-coded embedded broker addresses from custom client configuration.

Do not add a fictional `spring.kafka.embedded.enabled=false` property. Prove your change by running both profiles. [7][8]

**Checkpoint:** `dev` starts its embedded broker; `aws` starts Kafka clients only and connects to Lightsail.

## 6. Complete the Spring properties

Use the supplied `application.properties` as your starting point. Keep shared serializers, deserializers, logging and Swagger settings above the `#---` profile separators. Use ordinary ASCII punctuation in comments if your editor saves properties as ISO-8859-1.

Retain the development defaults:

```properties
spring.profiles.default=dev
server.port=8080
```

Keep H2 under the `dev` section. Complete the following AWS section yourself:

```properties
#---
spring.config.activate.on-profile=aws

server.port=5000

spring.datasource.url=jdbc:postgresql://roflgator-postgres.livefree.click:xxxx/xxxx?sslmode=require
spring.datasource.username=TODO
spring.datasource.password=${SPRING_DATASOURCE_PASSWORD}
spring.datasource.driver-class-name=TODO

spring.jpa.hibernate.ddl-auto=update
spring.jpa.open-in-view=false
spring.h2.console.enabled=false

spring.kafka.bootstrap-servers=roflgator-linux.livefree.click:9092
spring.kafka.security.protocol=PLAINTEXT
```

Fill in the PostgreSQL port, your logical database name, username and JDBC driver class. Set the password through the runtime environment. Do not leave `TODO`, `xxxx` or the example token in your final configuration.

Check `pom.xml` for the PostgreSQL JDBC driver (`org.postgresql:postgresql`, runtime scope) and retain the H2 dependency for local development. Let the existing Spring Boot dependency management select compatible versions.

`ddl-auto=update` can create/update tables inside an existing database; it does not create your named PostgreSQL database. Use it for this lab, not as a substitute for controlled production schema migrations.

**Explain:** Why must both the producer and consumers use the same student database when Kafka messages carry only a purchase ID?

## 7. Test the AWS profile locally before deploying

In your IDE, activate only `aws`, or in PowerShell use:

```powershell
$env:SPRING_PROFILES_ACTIVE="aws"
$env:SPRING_DATASOURCE_PASSWORD="REPLACE-WITH-INSTRUCTOR-PASSWORD"
.\mvnw.cmd spring-boot:run
```

Supply the real password through your IDE or secure local environment handling; do not submit it in your lab evidence. On Linux/macOS use `./mvnw` with equivalent environment variables.

| Runtime | Local page URL | Infrastructure |
| --- | --- | --- |
| `dev` | `http://localhost:8080` | H2 and embedded Kafka |
| `aws`, run on your laptop | `http://localhost:5000` | Lightsail PostgreSQL and Kafka |
| `aws`, run in Beanstalk | Environment HTTP/HTTPS URL, without `:5000` | Lightsail PostgreSQL and Kafka |

Check the startup log for the active profile, database connection, port 5000 and successful Kafka listener assignments. No embedded broker should start.

Open `http://localhost:5000/swagger-ui.html` or the HTML pages still in the application's static directory. Confirm the API Base URL input uses port 5000; an older saved value may still say 8080.

Use Swagger's actual request schemas to create the required customer/product/purchase data. Do not assume your old H2 rows exist in the new PostgreSQL database. Investigate any startup data initializer to ensure it does not delete or duplicate AWS data on every restart.

Test a valid queued purchase and observe `VALIDATED`, followed by `COMPLETED`. The supplied processing listener deliberately waits **60 seconds**. Test a product with a non-positive price or quantity and expect `REJECTED` if the application's request validation allows creating that test case.

To observe topic messages:

```bash
sudo docker exec -it -e KAFKA_HEAP_OPTS="-Xms32m -Xmx128m" kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:19092 --topic roflgator-purchases.process --from-beginning
```

Replace the token; press Ctrl+C to stop. Retained messages are purchase IDs, not complete purchase objects. Reading does not delete them. Do not join the application's consumer group just to inspect messages.

**Timing investigation:** A 60-second record handler can exceed `max.poll.interval.ms` when several records are returned in one poll. For this slow demonstration, consider `spring.kafka.consumer.max-poll-records=1`; explain why before adding it. Three partitions do not automatically create three listener threads in one application.

Stop your local AWS-mode application before final Beanstalk testing. Otherwise it can consume records alongside the deployed instance and make the deployment appear to work when the local process actually handled the purchase.

## 8. Prepare the API for an S3 frontend

S3 and Beanstalk have different origins. Configure **CORS in Spring**, not just in the S3 bucket.

Using a Spring MVC `WebMvcConfigurer` or existing controller CORS configuration:

- Allow the exact student website origin, such as `http://www-roflgator.livefree.click`.
- Cover both `/api/**` and `/queue/**` routes used by the supplied navigation.
- Allow the HTTP methods and headers your pages actually send, including JSON content-type and successful preflight OPTIONS handling.
- If Spring Security is present, integrate its CORS handling and inspect authorization/CSRF behavior rather than disabling security blindly.
- If temporarily testing the raw S3 website endpoint, that is a different origin and needs its own allowance.

Use the official Spring CORS guide as your implementation hint. [9]

For this initial public S3 website lab, use HTTP website URLs explicitly and only non-sensitive demo data. DNS records do not create HTTPS certificates. An HTTPS frontend cannot fetch an HTTP backend due to mixed-content blocking. A later HTTPS extension requires CloudFront for the S3 website and certificate/listener configuration for the backend.

**Checkpoint:** Explain why Swagger served by the backend can work while the same request from S3 is blocked by the browser.

## 9. Build and deploy the executable JAR to Beanstalk

1. Confirm the student names, AWS profile, CORS changes and embedded-broker refactor are complete.
2. Check the project Java version and choose a compatible **Java SE / Corretto** Beanstalk platform. This lab deploys an executable Spring Boot JAR, not a WAR to Tomcat.
3. Run your normal build from the project root:

```powershell
.\mvnw.cmd clean package
```

Do not skip failed tests just to get a JAR. Diagnose which profile and infrastructure the tests require. If a PostgreSQL password is required during the build, supply it through the environment, not source control.

4. Locate the executable application JAR under `target/`. Do not select `.jar.original`, a sources JAR or a test JAR.
5. Sanity-check it locally with the AWS environment variables set:

```powershell
java -jar target/YOUR-APPLICATION.jar --spring.profiles.active=aws
```

Stop that local process after the check.

6. Create or select the instructor-approved Beanstalk web environment. Configure its service role and EC2 instance profile as directed by the instructor. Upload the executable JAR as the application version.
7. Set these Beanstalk environment properties before expecting the deployment to become healthy:

```text
SPRING_PROFILES_ACTIVE=aws
SPRING_DATASOURCE_PASSWORD=<instructor-provided password>
```

8. Confirm the app listens on port **5000**, matching the Java SE proxy's default upstream port. Public requests normally arrive on port 80 or configured HTTPS 443, not port 5000. [10]
9. Ensure the Beanstalk instances have outbound connectivity to PostgreSQL 5432 and Kafka 9092, and that the Kafka firewall accepts their outbound source addresses.
10. Check environment health and application logs. If a load balancer is used, choose an existing successful HTTP health-check path, such as `/` if the static index is retained, or a configured health endpoint.
11. Test the Beanstalk environment's own URL with Swagger and an actual queued purchase before adding DNS.

**Profile reminder:** Building while `aws` is active does not permanently select that profile inside the JAR. Beanstalk must activate `aws` at runtime. A packaged `spring.profiles.default=dev` is fine only when the deployment supplies the AWS override.

## 10. Add the Beanstalk Route 53 record

Create `app-roflgator`, replacing the token:

| Setting | Value to choose |
| --- | --- |
| Record type | `A` |
| Alias | Enabled |
| Target | Your regional Beanstalk environment |
| Region | The actual environment Region |
| Routing | Simple |

Use an **alias A record**, not an ordinary A record with an environment hostname pasted into the IP field. Do not pin the record to a temporary resolved load-balancer/instance IP.

If the environment is not an eligible alias target, inspect its regional endpoint and account. For this subdomain, a CNAME to the environment hostname is a valid fallback; do not keep both records at the same name. [11]

Test `http://app-roflgator.livefree.click/swagger-ui.html` after substituting your name. If HTTPS has been explicitly configured, test the HTTPS version with its valid certificate instead. Merely creating the record does not enable HTTPS.

## 11. Point the S3 JavaScript at your deployed API

Edit the **file** `common.js` in your local static assets, then re-upload it to S3.

In the supplied code, `defaultBaseUrl()` returns `window.location.origin` whenever an origin exists. When hosted on S3, that returns the frontend's origin, so requests go to S3 instead of Beanstalk. Changing only the `localhost:8080` fallback will not fix that branch.

**Your task:** Change the default selection so the deployed frontend uses your backend base URL, for example:

```text
http://app-roflgator.livefree.click
```

Replace the name and use the scheme actually configured on Beanstalk. Use the base URL only: no `/swagger-ui.html`, `/api` suffix or port 5000. Preserve the editable API Base URL input if you want to switch between local and deployed testing.

Read `initBaseUrlInput()` and `getBaseUrl()` before assuming your edit failed. They prefer a saved value under the localStorage key `storeDemoBaseUrl`. On your S3 website, clear that override using the browser console:

```javascript
localStorage.removeItem("storeDemoBaseUrl");
location.reload();
```

Alternatively, set the visible API Base URL input to your new backend URL. Re-upload the changed `common.js`, hard-refresh, and inspect the Network tab to confirm the browser fetched the new file. Verify the JavaScript response has an appropriate content type rather than an HTML error page.

**Final test:** From the S3 custom hostname, create and submit a purchase. Verify the request goes to the Beanstalk custom hostname, the row appears in your PostgreSQL database, and your Kafka consumers move it through the expected statuses with your laptop application stopped.

## Troubleshooting checkpoints

| Symptom | Investigate |
| --- | --- |
| S3 `NoSuchBucket` | Bucket name must match the requested website hostname |
| S3 403 | Public-read policy, Block Public Access and index object |
| Broken CSS or links | Missing assets, wrong filename case or extra upload directory |
| Website tries HTTPS and fails | Direct S3 website hosting is HTTP-only |
| PostgreSQL database does not exist | Create the student SQL database; resource names are different |
| PostgreSQL driver not found | Runtime driver dependency and driver-class setting |
| PostgreSQL authentication fails | Username/password, runtime environment and correct endpoint |
| Kafka bootstrap disconnected | DNS, 9092 firewall, broker logs, advertised hostname and memory |
| Java native memory allocation fails in CLI | Keep the small `docker exec -e KAFKA_HEAP_OPTS=...` override; inspect `free -h` |
| Topic exists but listeners see nothing | Exact producer/listener/topic strings, group offsets and active profile |
| Purchase ID cannot be found | Student database mismatch or retained messages referencing old data |
| Embedded Kafka starts in AWS | Remaining static startup call or incorrectly activated profiles |
| Beanstalk 502/unhealthy | Java version, executable JAR, port 5000, profile and startup logs |
| Browser CORS error | Backend origin/method/header rules and security preflight handling |
| API calls still go to S3/localhost | `defaultBaseUrl()` origin branch, localStorage or cached JavaScript |
| Purchases handled but no Beanstalk log | A local AWS-mode consumer may still be running |
| Processing repeatedly rebalances | Long handler plus too many records per poll |

## Submit your evidence

- Your completed naming table and DNS record types/targets, without credentials.
- S3 website custom URL and a screenshot showing CSS and navigation loaded.
- PostgreSQL query showing your logical database name.
- Topic descriptions showing the two names and three partitions each.
- Source diff showing consistent topic/group names and the embedded startup fix.
- AWS startup log showing profile `aws` and port 5000, with credentials removed.
- A browser Network request from S3 to your Beanstalk custom hostname.
- One valid purchase completing and one rejected test, where supported.
- A short explanation of bootstrap versus advertised Kafka addresses, and why CORS is required.

## Cleanup

Follow your instructor's retention instructions. Delete only your own approved Beanstalk environment and student resources. Remove website DNS before deleting its matching S3 bucket to avoid leaving a dangling hostname. Do not delete the shared broker, Docker volume, Linux instance or managed database. Coordinate any topic/database deletion with the instructor.

## Official references

1. [Docker Engine on Ubuntu](https://docs.docker.com/engine/install/ubuntu/)
2. [PostgreSQL official Docker image](https://hub.docker.com/_/postgres)
3. [Route 53 routing to an S3 website](https://docs.aws.amazon.com/Route53/latest/DeveloperGuide/RoutingToS3Bucket.html)
4. [S3 website endpoints](https://docs.aws.amazon.com/AmazonS3/latest/userguide/WebsiteEndpoints.html)
5. [Connecting to Lightsail PostgreSQL](https://docs.aws.amazon.com/lightsail/latest/userguide/amazon-lightsail-connecting-to-your-postgres-database.html)
6. [PostgreSQL JDBC SSL configuration](https://jdbc.postgresql.org/documentation/ssl/)
7. [Spring Kafka embedded broker support](https://docs.spring.io/spring-kafka/reference/testing.html)
8. [Spring profiles and environment](https://docs.spring.io/spring-framework/reference/core/beans/environment.html)
9. [Spring MVC CORS](https://docs.spring.io/spring-framework/reference/web/webmvc-cors.html)
10. [Beanstalk Java SE proxy configuration](https://docs.aws.amazon.com/elasticbeanstalk/latest/dg/java-se-nginx.html)
11. [Route 53 routing to Beanstalk](https://docs.aws.amazon.com/Route53/latest/DeveloperGuide/routing-to-beanstalk-environment.html)
