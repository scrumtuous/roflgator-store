# Simple store purchases with Spring Kafka

## Run

Requires Java 17+ and Maven. From this folder:

```sh
mvn spring-boot:run
```

Or run `com.mcnz.store.App` in your IDE. Kafka and H2 start automatically; no Docker
or separate installation is needed. Dependencies download on the first build.
This pins Spring Boot 3.5.0 as a teaching baseline, not a latest-version claim.

## Run with Docker

Build the image from this project folder:

```powershell
docker build -t simple-kafka-store:local .
```

Run using defaults from `application.properties` (`aws` profile by default in this repo).
This means remote PostgreSQL + remote Kafka unless you override properties:

```powershell
docker run --rm -p 8080:8080 simple-kafka-store:local
```

Override only the datasource password at runtime:

```powershell
docker run --rm -p 8080:8080 `
  -e SPRING_DATASOURCE_PASSWORD='replace_me' `
  simple-kafka-store:local
```

Change profile at runtime (example: `dev`):

```powershell
docker run --rm -p 8080:8080 `
  -e SPRING_PROFILES_ACTIVE=dev `
  simple-kafka-store:local
```

### Kafka modes in Docker

- `aws` profile (default in this project): **remote Kafka** from `spring.kafka.bootstrap-servers`
- `dev` profile: uses embedded local Kafka (if you do not set `spring.kafka.bootstrap-servers`)

Remote Kafka explicitly (recommended for `aws` profile):

```powershell
docker run --rm -p 8080:8080 `
  -e SPRING_PROFILES_ACTIVE=aws `
  -e SPRING_KAFKA_BOOTSTRAP_SERVERS='linux.livefree.click:9092' `
  -e SPRING_DATASOURCE_URL='jdbc:postgresql://postgres.livefree.click:5432/roflgator-store?sslmode=require' `
  -e SPRING_DATASOURCE_USERNAME='dbmasteruser' `
  -e SPRING_DATASOURCE_PASSWORD='replace_me' `
  simple-kafka-store:local
```

Local/embedded Kafka (dev profile):

```powershell
docker run --rm -p 8080:8080 `
  -e SPRING_PROFILES_ACTIVE=dev `
  simple-kafka-store:local
```

Use command-line Spring overrides instead of `-e` env vars if preferred:

```powershell
docker run --rm -p 8080:8080 simple-kafka-store:local --spring.datasource.password=replace_me --spring.profiles.active=aws
```

## The whole flow

1. `App` loops through the existing PURCHASE rows and calls `Producers.submit()`.
2. `submit()` sends each row to **purchases.validate**.
3. `Consumers.validate()` checks quantity and price, then sends valid rows to **purchases.process**.
4. `Consumers.process()` calls the existing `StoreService.processPurchase()` to save to H2.

Start with `Producers.java` and `Consumers.java`: together they contain the queue
workflow in small, easy-to-read methods. There are no lambdas, streams, Cloud
Stream binders, priority queues, status entities, or new database models. Kafka
calls its queues "topics".

## Changes to your files

- `App`: sends PURCHASE rows to Kafka rather than saving directly.
- `StoreService`: removed the old synchronous row dispatcher; made `processPurchase`
  public and transactional. Its customer/purchase/product logic is retained.
  Removed the two lambda-based sorts from the report; it now uses ordinary loops.
- `Customer`, `Purchase`, `Product`, `Address`: restored the missing JPA annotations
  so the uploaded annotation exercise can run. Added `@JsonIgnore` on backreferences
  for the optional JSON endpoint.
- `CustomerRepository`, `StorePurchasesData`, and `StoreRestMockData` are included in `com.mcnz.store.repositories`
  as supplied. The existing `StoreRestController` lambda checks were replaced with
  ordinary if statements. The controller and mock data remain unfinished exercise
  code, not active endpoints; `/queue/customers` shows actual saved data.
- Three new classes live in `com.mcnz.store.messaging`: `Producers`,
  `Consumers`, and `FakeKafka`.

The original behavior of grouping purchase lines by customer and date is preserved.
The startup dataset contains refund rows too. Those are deliberately skipped in
this purchase-only exercise: processing refunds immediately would race the queued
purchases. The original refund method remains available in StoreService, but is
not called automatically. Consequently the report contains purchases before refunds.

## See results

Open http://localhost:8080/queue/customers to see real customers and purchases.
Open http://localhost:8080/queue/report to print the existing report in the console.
Wait for processing to finish first; submission is asynchronous.

Admin page: http://localhost:8080/admin.html

- Shows active/default profile and desired profile.
- Displays effective JDBC and Kafka settings, plus configured topic names.
- Tests arbitrary PostgreSQL and Kafka targets before applying.
- Attempts live (hot) switch for database and Kafka first, then reports restart fallback if needed.
- Includes in-memory logs and file tail for troubleshooting.
- Provides `POST /admin/restart` to intentionally exit the JVM so a container restart policy can relaunch the app.

Container-local persistence for admin changes:

- Runtime overrides file: `runtime/admin-overrides.properties`
- Application log file: `runtime/app.log`

These files are inside the running container/app filesystem. Without a Docker volume
mount, they persist only for that container instance and are lost when the container
is replaced.

H2 console: http://localhost:8080/h2-console
JDBC URL: `jdbc:h2:mem:storedb`, user: `sa`, password: blank.

## Submit another purchase

PowerShell:

```powershell
Invoke-RestMethod -Method Post -Uri http://localhost:8080/queue/purchases -ContentType 'application/json' -Body (Get-Content -Raw example-purchase.json)
```

Or:

```sh
curl -H "Content-Type: application/json" --data-binary @example-purchase.json http://localhost:8080/queue/purchases
```

Change quantity to 0 to see REJECTED in the console. A rejected purchase is not saved.
The HTTP 202 response means submitted, not completed. Assume well-formed messages
with valid dates and populated fields; this is intentionally a happy-path lesson.

## Small details worth teaching

- The customer name is the Kafka message key. With three partitions per topic,
  identical names go to the same partition. Different names may share a partition.
- Each listener has one consumer for simplicity; no extra concurrency configuration.
- `.join()` waits for Kafka to acknowledge publishing, not for downstream processing.
- `@Transactional` covers the database work inside StoreService; it does not make
  the Kafka delivery and database update one atomic transaction. Retries/replay can
  duplicate purchases; no custom retries, outbox, or deduplication is added here.
- PENDING/VALIDATED/COMPLETED are not persisted statuses in this simplified version.
  The topic and console output show the stage.
- The embedded Kafka helper is classroom/test infrastructure. Kafka uses temporary
  disk logs; H2 is in memory. The demo starts fresh on restart.

## Add to your existing project

Copy the updated store classes and the three `com.mcnz.store.queues` classes, and merge the
Kafka dependencies and properties into your own build. App scans both packages;
entities and the repository remain in com.mcnz.store. The line
`EmbeddedKafkaBackend.start()` runs before `SpringApplication.run()`.
Do not copy this demo's `create-drop` database setting into a database you want to keep.

For an external Kafka broker later, remove that startup line, configure
`spring.kafka.bootstrap-servers`, and create the same two topics on that broker.
The producer/consumer code remains the same.

## Profile switching notes

Profile changes are shown and can be queued from the admin page. A true live profile
switch is not fully supported because Spring beans are created at startup. The app
returns explicit restart-required messages when this happens.
