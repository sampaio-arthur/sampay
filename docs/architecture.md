# Sampay: backend study project architecture

Oct 23, 2026 · @arthur silveira sampaio

## Overview

Sampay is a digital wallet with internal and external Pix, written by hand as a study of distributed backend systems: five Java microservices, Kafka, an orchestrated saga, CQRS with PostgreSQL and MongoDB, and tests that prove the system withstands failures of its environment.

**Goal.** Have an end-to-end backend that I know inside out and that answers the classic interview questions with evidence: concurrency, idempotency, consistency across services, duplicate messages, slow dependencies, overloaded endpoints. Once validated, the project goes to production.

**Domain.**

- Wallet accounts, with balance and entries.
- Internal transfer between wallet accounts: synchronous, in a local transaction.
- Pix to another bank: asynchronous, a saga with antifraud, funds reservation and settlement in a simulated SPI.
- Statement queried at high volume.

**Principles.**

- The code is robust from the first version. What fails is the environment (database, broker, network, external dependency, concurrent requests), and the tests simulate those failures to prove the system holds up.
- Every guarantee becomes a testable invariant (section Invariants and tests).
- Small modules with explicit dependencies, so each part can be isolated and tested.
- No component enters the project before a concept or a test needs it.
- Design patterns come in when they solve a concrete problem, never just for show.

## Architecture map

The gateway is the only external entry point, and services only talk to each other through Kafka. The only synchronous call between components is payment calling the SPI.

&#91;embedded content: architecture · 5 services, gateway, simulator and Kafka\]

Observability, Toxiproxy and Consul are left out of the diagram to keep it clean; every service talks to them.

## Stack

Java 21 with Spring Boot 4.1 and Spring Cloud 2025.1, all in multi-module Maven. Exact versions are pinned in the parent `pom.xml` on the day Phase 0 starts.

| Layer | Choice | Why |
| --- | --- | --- |
| Language | Java 21 (LTS) | Most common version in job postings. Virtual threads available |
| Framework | Spring Boot 4.1.x | The 4.0 line loses open source support on 12/31/2026 |
| Spring Cloud | 2025.1.x (2025.1.2 or later) | 2025.1.2 is the first one compatible with Boot 4.1. Brings Gateway, Consul, CircuitBreaker and Contract |
| Build | Multi-module Maven, with BOMs in the parent pom | Standard in banking. Modules enforce the dependency rules at compile time |
| API | Spring Web MVC + virtual threads, springdoc-openapi, Problem Details (RFC 9457) | Imperative model, easy to debug. WebFlux stays out: almost all of its gain comes from virtual threads |
| Relational | PostgreSQL, Spring Data JPA, Flyway | Writes, transactions, locks, constraints |
| Document | MongoDB, Spring Data MongoDB | Statement read model |
| Messaging | Apache Kafka 4.x (KRaft only) with Spring for Apache Kafka | Used directly, without Spring Cloud Stream, to see offsets, partitions and commits |
| Queue and files | SQS and S3 via LocalStack | AWS requirement from the job posting, at no cost. Allows comparing a queue with a log |
| Message format | JSON with a versioned envelope | Avro with Schema Registry is a later evolution |
| Cache and counters | Redis | Antifraud counters, statement cache, rate limit |
| Resilience | Resilience4j | Timeout, retry, circuit breaker, bulkhead |
| Discovery and config | Consul + Spring Cloud Consul | Requirement from the job posting |
| Gateway | Spring Cloud Gateway | Routing, JWT, rate limit, correlation id |
| Security | Keycloak + OAuth2 Resource Server (JWT) | You already know it; focus on how the token travels across services |
| Observability | Micrometer, OpenTelemetry, Prometheus, Tempo, Loki, Grafana | Starts with the all-in-one `grafana/otel-lgtm` image and is split later |
| Tests | JUnit, AssertJ, Mockito, Awaitility, Testcontainers, Toxiproxy, WireMock, ArchUnit, Spring Cloud Contract, k6 | Detailed in Invariants and tests |
| CI/CD | Jenkins (declarative Jenkinsfile) | Requirement from the job posting |
| Containers | Docker, Docker Compose, local Kubernetes with kind | Compose day to day, kind in the platform phase |

Object mapping is done by hand, without MapStruct: this is a study project and every conversion between layers should be visible.

Versions checked on 10/23/2026: [Spring Cloud 2025.1.2](https://spring.io/blog/2026/06/11/spring-cloud-2025-1-2-aka-oakwood-has-been-released), [Spring Boot support lifecycle](https://www.herodevs.com/blog-posts/spring-boot-versions-eol-dates-and-latest-releases-april-2026).

## Monorepo and modules

One repository, one parent `pom.xml` and four Maven submodules per service. The only production code shared between services is the message contracts.

```
sampay/
├── pom.xml                      ← versions, BOMs, plugins, module list
├── Jenkinsfile
├── platform/
│   ├── contracts/               ← command and event records + envelope. Data only, no logic
│   └── test-support/            ← test scope: ready-made containers, Toxiproxy, fixture builders
├── services/
│   ├── payment-service/
│   │   ├── domain/
│   │   ├── application/
│   │   ├── adapters/
│   │   └── bootstrap/           ← builds the executable jar and the image
│   ├── ledger-service/          ← same structure
│   ├── antifraud-service/
│   ├── statement-service/
│   └── notification-service/
├── gateway/                     ← Spring Cloud Gateway, single module
├── simulators/
│   └── spi-simulator/           ← WireMock with the SPI scenarios
├── infra/
│   ├── compose/                 ← docker-compose per profile (core, full)
│   ├── observability/           ← prometheus.yml, Grafana dashboards
│   ├── toxiproxy/
│   └── k8s/
├── load-tests/                  ← k6 scripts
└── docs/
    ├── adr/                     ← one architecture decision per file
    └── experiments/             ← one record per failure experiment
```

**Why only contracts are shared.** A `common` module with entities, utilities and rules makes every service change together and turns into a distributed monolith. Contracts are different: they are exactly the boundary that services agree on, and changing a contract requires coordination anyway.

**Selective build.** A change only in the ledger compiles and tests only the ledger and what depends on it (`mvn -pl services/ledger-service/bootstrap -am verify`). Jenkins uses this to avoid building the whole monorepo on every commit.

**ADRs.** Every relevant decision (pessimistic or optimistic lock, polling outbox, orchestration) becomes a short file with context, decision and consequences. This is also interview material: it is the written answer to "why did you do it this way?".

## Layers of each service

Every service follows hexagonal architecture in four modules, and dependencies only point inward: `bootstrap → adapters → application → domain`. The domain knows nothing about Spring, the database or Kafka.

&#91;embedded content: layers of a service · 4 Maven modules\]

Solid arrows are the call path; the dashed one shows that outbound adapters implement the ports defined in `application`, which is why the dependency still points inward.

| Module | Contains | Depends on | Forbidden |
| --- | --- | --- | --- |
| `domain` | Entities, value objects (`Money`, `AccountId`), rules, state machines, business exceptions, domain events | Only the JDK | Any framework, JPA annotation, I/O |
| `application` | Use cases, inbound ports (use case interfaces) and outbound ports (repositories, outbox, clock, unit of work), commands and results | `domain` | Spring, JPA, Kafka, HTTP |
| `adapters` | **Inbound:** REST controllers, Kafka listeners. **Outbound:** JPA or Mongo, outbox relay, HTTP clients, SQS, S3 | `application`, `domain`, frameworks | Business rules |
| `bootstrap` | Main class, `@Configuration` that instantiates the use cases, `application.yml`, profiles | All | Logic of any kind |

**How a request crosses the layers.**

1. The controller receives the JSON, validates its format and converts the DTO into a command (`ReserveFundsCommand`).
2. It calls the inbound port (`ReserveFundsUseCase`). It does not know the implementation.
3. The use case opens the transaction through the `UnitOfWork` port, loads the aggregate through the repository port, calls the domain method (`account.reserve(amount)`) and writes the result and the outgoing message through the outbox port.
4. The JPA adapter converts the domain entity into the JPA entity and persists it. The commit covers state and outbox together.
5. The result goes back to the controller, which builds the HTTP response. Domain exceptions become Problem Details in a `@RestControllerAdvice`.

A Kafka listener follows the same path: it converts the message into a command and calls the same use case. The rule does not know whether it came from HTTP or Kafka.

**Decisions in this layer.**

- **Transaction through a port, not an annotation.** `application` does not depend on Spring, so it does not use `@Transactional`. The use case calls `unitOfWork.execute(() -> …)` and the adapter implements it with `TransactionTemplate`. It makes explicit where the transaction starts and ends, which matters for understanding the outbox.
- **Domain entity separate from the JPA entity.** It costs one mapper per aggregate. In return, the domain carries no Hibernate proxies, lazy loading or mandatory empty constructor.
- **Use cases as beans created in `bootstrap`.** No `@Service` annotation in `application`. Wiring lives in a single place.
- **ArchUnit in the build.** Rules that Maven cannot catch become tests: a controller does not access a repository, an inbound adapter does not call an outbound adapter, no `domain` class throws a framework exception.

## Services

Five business services, one gateway and one simulator. Each service owns its database; none reads another's database.

| Service | Role | Database | Receives | Produces |
| --- | --- | --- | --- | --- |
| payment-service | Orchestrates the external Pix saga | PostgreSQL `payment_db` | REST, `payment.saga-replies` | `antifraud.commands`, `ledger.commands`, `payment.events`, HTTP to the SPI |
| ledger-service | Source of truth for money | PostgreSQL `ledger_db` | REST, `ledger.commands` | `payment.saga-replies`, `ledger.events` |
| antifraud-service | Approves or rejects payments | Redis | `antifraud.commands` | `payment.saga-replies` |
| statement-service | Statement (read side) | MongoDB `statement_db` | `ledger.events`, REST | — |
| notification-service | Receipt and customer notice | — | `payment.events` | S3, SQS |
| gateway | Single entry point | Redis (rate limit) | External REST | Internal REST |
| spi-simulator | Pretends to be the settlement system | — | HTTP | — |

### payment-service

Receives the Pix request, drives the saga to a final state and records which step each payment is in.

- **Endpoints:** `POST /pix-payments` (header `Idempotency-Key`) responds `202 Accepted` with `Location`; `GET /pix-payments/{id}` shows status and history.
- **Tables:** `pix_payment` (id, unique idempotency\_key, payer\_account\_id, payee\_key, amount, status, version, timestamps); `saga_instance` (payment\_id, step, status, deadline\_at, attempts, version); `outbox_event`; `processed_message`.
- **Patterns:** Orchestrator; State in the state machine; Outbox; Adapter in the SPI client; Circuit Breaker and Timeout; Idempotency Key; scheduler for expired deadlines and reconciliation.

### ledger-service

Accounts, balance, reservations and entries. It also runs the internal transfer, which is synchronous.

- **Endpoints:** `POST /accounts`; `GET /accounts/{id}` (strongly consistent balance); `POST /internal-transfers` (header `Idempotency-Key`).
- **Tables:** `account` (id, owner, balance, reserved, version, status) with `CHECK (reserved >= 0 AND balance >= reserved)`; `ledger_entry` (id, account\_id, reference\_id, type DEBIT or CREDIT, amount, created\_at), which is never updated or deleted; `funds_reservation` (id, account\_id, unique payment\_id, amount, status HELD, CAPTURED or RELEASED); `internal_transfer` (id, unique idempotency\_key, from, to, amount); `outbox_event`; `processed_message`.
- **Available balance** = `balance - reserved`. The `CHECK` is the last line of defense: if a bug slips past the code, the database refuses the negative balance.
- **Patterns:** Value Object (`Money`); Repository; pessimistic or optimistic lock (decided with numbers in Phase 1); fixed lock order against deadlock; Outbox; Idempotency.

### antifraud-service

Applies simple rules and answers approved or rejected. It has no relational database.

- **Rules:** per-transaction limit, night-time limit, velocity (number of Pix payments per account in the last hour), blocked destination key.
- **Redis:** the velocity rule uses one sorted set per account, with the `paymentId` as member and the timestamp as score. Adding the same payment twice does not change the count, so the rule is idempotent from the start.
- **Kafka transactions:** consuming the command and publishing the reply happen in a Kafka transaction (consume-transform-produce). It is the counterpoint to the outbox: outbox when the state is in a database; Kafka transaction when input and output are both in Kafka.
- **Patterns:** Strategy per rule, Composite to combine them.

### statement-service

Builds the statement from the ledger events. It is the most queried endpoint in the system.

- **Endpoints:** `GET /accounts/{id}/statement?limit=&cursor=` (cursor pagination); `GET /accounts/{id}/summary` (projected balance and time of the last event).
- **Collections:** `statement_entries` with the `eventId` as `_id` and index `{accountId: 1, occurredAt: -1, _id: -1}`; `account_summary`.
- **Natural idempotency:** since the `_id` is the event id, a redelivered event causes a duplicate key error and is ignored.
- **Patterns:** CQRS (read model), Projection, cursor pagination, cache-aside with Redis (Phase 9).

### notification-service

Reacts to the end of the payment, without taking part in the saga.

- Consumes `PixPaymentCompleted` and `PixPaymentFailed`, stores the receipt (JSON) in S3 and puts the notice on an SQS queue, which stands in for an external push provider.
- Shows in practice the difference between Kafka (a log that several consumers reread) and SQS (a queue where each message is delivered to one consumer and removed).

### gateway and spi-simulator

- **gateway:** routes to the services (fixed at first, via Consul later), validation of the Keycloak JWT, per-client rate limit in Redis and generation of `X-Correlation-Id`.
- **spi-simulator:** WireMock with `POST /settlements` (idempotent by `paymentId`) and `GET /settlements/{paymentId}`. Slowness and error scenarios are switched on per test.

## Messaging

Commands and events live in separate topics, delivery is at-least-once and every consumer is idempotent. Exactly-once only exists inside Kafka, and is used only in antifraud.

**Command vs event.** A command is a request to a specific recipient, in the imperative (`ReserveFunds`), and can be refused. An event is a fact that already happened, in the past tense (`FundsReserved`), published for whoever wants to listen.

| Topic | Type | Messages | Partition key | Producer | Consumer |
| --- | --- | --- | --- | --- | --- |
| `antifraud.commands` | Command | `EvaluatePixPayment` | paymentId | payment | antifraud |
| `ledger.commands` | Command | `ReserveFunds`, `CaptureFunds`, `ReleaseFunds` | accountId | payment | ledger |
| `payment.saga-replies` | Reply | `FraudCheckApproved`, `FraudCheckRejected`, `FundsReserved`, `FundsReservationRejected`, `FundsCaptured`, `FundsReleased` | paymentId | antifraud, ledger | payment |
| `ledger.events` | Event | `AccountOpened`, `EntriesPosted` | accountId | ledger | statement |
| `payment.events` | Event | `PixPaymentCompleted`, `PixPaymentFailed` | payerAccountId | payment | notification |
| `<topic>.dlt` | Dead letter | Messages that ran out of attempts | same as the original | error handler | you, manually |

**Why the key for ledger commands is the account.** All operations on an account land in the same partition and are processed in order by a single consumer. This reduces lock contention on the same row and guarantees that the reservation arrives before the capture.

**Envelope.** Every payload travels inside the same envelope, defined in `platform/contracts`:

```json
{
  "messageId": "unique uuid of this message",
  "type": "ReserveFunds",
  "version": 1,
  "occurredAt": "2026-10-23T14:00:00Z",
  "correlationId": "paymentId: links all messages of the same saga",
  "causationId": "messageId of the message that caused this one",
  "payload": { }
}
```

The tracing context (`traceparent`) goes in the Kafka headers, not in the body.

**Outbox.** Every service with Postgres writes its outgoing messages to `outbox_event` (id, topic, key, type, payload jsonb, created\_at, published\_at) in the same transaction as the state. A relay reads pending batches with `FOR UPDATE SKIP LOCKED`, publishes, waits for the broker ack and marks them as published. If the relay dies between publishing and marking, the message goes out again: that is why idempotency on the other side is mandatory. Debezium (CDC) is a later evolution.

**Consumer idempotency.** The `messageId` goes into `processed_message` in the same transaction as the effect. If the message arrives again, the duplicate key makes the consumer skip the effect and just commit the offset. In Mongo, the document `_id` plays this role.

**Configuration.**

- Producer: `acks=all`, `enable.idempotence=true`.
- Consumer: no auto commit; the offset is committed after processing.
- Errors: `DefaultErrorHandler` with exponential backoff and `DeadLetterPublishingRecoverer`. Deserialization errors go straight to the DLT, without retry.
- Topics, once there are 3 brokers: `replication.factor=3`, `min.insync.replicas=2`.

## Main flow: Pix to another bank

External Pix is a saga orchestrated by payment-service, with settlement at the SPI as the pivot: before it, every step has a compensation; after it, every step is retried until it succeeds.

&#91;embedded content: Pix saga state machine · 9 states\]

The left column is the happy path; the right one, the deviations. Every transition is written to the payment database before the next command goes out.

**Happy path.**

1. **Request.** The client calls `POST /pix-payments` with `Idempotency-Key`. In one transaction, payment writes the payment (`CREATED`), the saga instance and the `EvaluatePixPayment` command to the outbox. It responds `202` with the address to follow it.
2. **Antifraud.** The relay publishes the command. Antifraud evaluates it and, in the same Kafka transaction, publishes `FraudCheckApproved` and commits the offset.
3. **Reservation.** Payment moves to `FUNDS_RESERVATION_PENDING` and sends `ReserveFunds`. The ledger locks the account, checks the available balance, creates the reservation (`HELD`), adds to `reserved` and replies `FundsReserved`, all in one transaction.
4. **Settlement (pivot).** Payment writes `SETTLEMENT_PENDING` and commits. Only then does a worker call the SPI, outside any database transaction. The call is idempotent by `paymentId`, so it can be repeated without settling twice.
5. **Capture.** With the SPI confirming, payment writes `SETTLED` and sends `CaptureFunds`. The ledger marks the reservation as `CAPTURED`, debits `balance` and `reserved`, writes the debit entry and publishes `FundsCaptured` (reply) and `EntriesPosted` (event).
6. **End.** Payment writes `COMPLETED` and publishes `PixPaymentCompleted`. Statement updates the statement from `EntriesPosted`; notification stores the receipt and notifies the customer.

**Deviations.**

| Where | What happens | Saga reaction | Final state |
| --- | --- | --- | --- |
| Antifraud | `FraudCheckRejected` | Nothing to undo | `REJECTED` |
| Reservation | `FundsReservationRejected` (insufficient balance) | Nothing to undo | `FAILED` |
| Settlement | SPI refuses (business error, 4xx) | Sends `ReleaseFunds`; the ledger releases the reservation | `FAILED` (compensated) |
| Settlement | Timeout, 5xx or open circuit breaker | `SETTLEMENT_UNKNOWN`: queries `GET /settlements/{id}` with backoff until it knows whether it settled | depends on the query |
| Settlement | The query does not respond either | Reconciliation job tries later; past the limit, alert for manual intervention | pending, with alert |
| Capture | Ledger down | Command stays in the outbox or the topic; nothing is compensated, because the money has already left | `COMPLETED` when the ledger comes back |
| Any step | Participant never replies | Scheduler finds an expired `deadline_at` and resends the command (participants are idempotent); once attempts run out, it fails and compensates what came before | `FAILED` |

**A timeout is not a failure.** A timeout at the SPI does not say whether the Pix was settled; it only says the response did not arrive. Compensating at that moment would release the reservation of money that may already have left. That is why the `SETTLEMENT_UNKNOWN` state exists.

**Isolation.** A saga has no "I" from ACID: other flows see the intermediate states. The reservation works as a semantic lock: the reserved amount leaves the available balance, so a concurrent internal transfer cannot spend the same money.

**Choreography.** The implementation goes straight to orchestration. Choreography shows up where it makes sense: statement and notification only react to events, with nobody commanding them. A choreographed version of the saga may become an experiment later, to compare the two in practice.

## Secondary flows

### Internal transfer

Both accounts are in the same database, so a local transaction is enough. There is no saga, and that is the answer to "does every flow between accounts need to be asynchronous?".

1. `POST /internal-transfers` with `Idempotency-Key` reaches the ledger through the gateway.
2. In the transaction, the first step is inserting into `internal_transfer`. If the key already exists, it returns the result stored the first time, without repeating anything.
3. It locks both accounts with `SELECT … FOR UPDATE`, **always in ascending id order**. A→B and B→A at the same time request the locks in the same order, so one waits for the other instead of each holding what the other needs (deadlock).
4. It checks the available balance, updates both balances, writes both entries and puts `EntriesPosted` in the outbox.
5. Commit and `201 Created`.

In Phase 1 the same operation is also implemented with an optimistic lock (`@Version` and retry). Both versions go through the same load test, and the final choice becomes an ADR with the numbers.

### Statement query

1. `GET /accounts/{id}/statement?limit=50&cursor=…` goes through the gateway rate limit and reaches statement.
2. The query uses the index `{accountId, occurredAt desc, _id desc}` and fetches what comes after the cursor. The cursor is the (occurredAt, \_id) pair of the last item, base64 encoded.
3. The response carries `asOf`, the time of the last applied event, so the client knows how up to date the statement is.

**Why cursor and not offset.** `skip(10000)` makes the database walk through and discard 10 thousand documents on every page, and new entries at the top shift the pages. The cursor goes straight to the point through the index and neither repeats nor skips items.

### Two balances, two guarantees

| Endpoint | Source | Consistency | When to use |
| --- | --- | --- | --- |
| `GET /accounts/{id}` (ledger) | PostgreSQL | Strong | Before deciding something based on the balance |
| `GET /accounts/{id}/summary` (statement) | MongoDB, cached in Redis | Eventual, with `asOf` | Displaying on screen, high volume |

## Invariants and tests

The system promises nine guarantees, and each one has an automated test that provokes the environment failure and confirms the guarantee holds.

| # | Invariant | Where it is guaranteed | Test that proves it |
| --- | --- | --- | --- |
| 1 | Available balance never goes negative | Lock in the ledger + `CHECK` in the database | Hundreds of concurrent debits on the same account (threads and k6) |
| 2 | Sum of entries = account balance | Balance and entries in the same transaction | Reconciliation query run after every load test |
| 3 | The same `Idempotency-Key` produces a single effect and the same response | Unique constraint + stored response | Parallel requests with the same key |
| 4 | A redelivered message does not duplicate the effect | `processed_message` / `_id` = eventId | Resend the same message; kill the consumer before the offset commit |
| 5 | A confirmed event is never lost | Outbox | Cut Kafka with Toxiproxy during a load test and check that everything arrives later |
| 6 | Every saga ends in a final state, or raises an alert | Per-step deadlines, status query, reconciliation | SPI with no response; kill payment in the middle of the saga |
| 7 | A slow dependency does not exhaust the service | Timeout, circuit breaker, bulkhead | 10 s of latency at the SPI via Toxiproxy, under load |
| 8 | An invalid message does not block the partition | Error handler + DLT | Publish broken JSON straight to the topic |
| 9 | A change in one service does not break another | Versioned contracts | Contract tests in the build |

**Test layers.**

| Layer | What it covers | Tools | Runs in |
| --- | --- | --- | --- |
| Unit | Domain and use cases, with fake ports | JUnit, AssertJ, Mockito | Every build, seconds |
| Architecture | Dependency rules between layers | ArchUnit | Every build |
| Integration | Each adapter against the real dependency | Testcontainers, Awaitility | Every build |
| Contract | Format of events and APIs between services | Spring Cloud Contract | Every build |
| Component | One whole service, the rest simulated | Testcontainers, WireMock | Every build |
| Resilience | Invariants 4 to 8 under injected failure | Testcontainers, Toxiproxy | Nightly pipeline |
| End to end | Full saga with all services | Docker Compose | Before deploy |
| Load | p95/p99 latency, throughput, bottlenecks | k6 + Grafana | On demand, recorded in `docs/experiments` |

Asynchronous tests never use `Thread.sleep`: Awaitility waits until the condition is true, with a maximum timeout.

## Observability

Metrics tell you something is wrong, traces tell you where, logs tell you why. All three carry the same `traceId`, and the `correlationId` (the paymentId) links all messages of a saga.

| Signal | Tool | What to measure |
| --- | --- | --- |
| Metrics | Micrometer → Prometheus | Rate, errors and latency per endpoint; lag per consumer group; size of the pending outbox; sagas per state and age of the oldest; HikariCP pool; circuit breaker state; JVM |
| Traces | OpenTelemetry → Tempo | A whole saga in a single trace, across HTTP and Kafka (the context goes in the message headers) |
| Logs | Structured JSON → Loki | `traceId`, `correlationId` and `messageId` on every line |
| Dashboards and alerts | Grafana | One dashboard per service, one saga dashboard. Alerts: saga stuck past its deadline, message in the DLT, growing lag, outbox piling up |

At first everything runs on the `grafana/otel-lgtm` image, which bundles collector, Prometheus, Tempo, Loki and Grafana in one container. In the platform phase the components are split.

Dynatrace is left out because it is paid. The concepts (RED metrics, distributed tracing, log correlation) are the same, and that is what the interview asks about.

## Infrastructure, CI/CD and production

Everything runs locally on Docker Compose until the platform phase; production comes last, after all invariants pass.

**Local.**

- `core` profile: PostgreSQL, MongoDB, Kafka (1 broker) and Kafka UI. This is what you start day to day.
- `full` profile: adds Redis, Consul, Keycloak, LocalStack, Toxiproxy, spi-simulator and observability.
- One PostgreSQL instance with one database per service. Sharing the instance is acceptable in development; sharing tables between services, never.
- Kafka goes to 3 brokers only when it is time to test replication.

**Local AWS (S3 and SQS).** LocalStack runs in a container and answers with the same API as AWS on `localhost:4566`. The code uses AWS SDK v2 as usual; only the configuration changes. In the `local` profile, the client points to LocalStack (`endpointOverride`) with dummy credentials; in production, without an override, it uses the real endpoint and the environment's credentials. Bucket and queue are created by a container init script, and Testcontainers has its own module for the tests.

Since 03/23/2026 the LocalStack image requires an account and an auth token; there is a free plan for individual, non-commercial use ([LocalStack](https://blog.localstack.cloud/localstack-single-image-next-steps/)). Without an account, Moto (S3 and SQS) works as an alternative, without changing a line of code.

**Jenkins pipeline.**

1. Checkout and detection of changed modules.
2. Build with unit, ArchUnit and contract tests.
3. Integration and component tests (the agent needs Docker for Testcontainers).
4. Image build, tagged with the commit, and push to the registry.
5. Deploy to staging and end-to-end test.
6. Manual approval and deploy to production.

Resilience and load tests run in a separate nightly job, so they do not slow down the main pipeline.

**Path to production.** The decision is left for the end, with costs gathered at the time:

| Route | What it looks like | For | Against |
| --- | --- | --- | --- |
| A: managed AWS | ECS Fargate, RDS PostgreSQL, MongoDB Atlas, managed Kafka (MSK or Confluent Cloud), S3, SQS | It is the stack from the job posting | Managed Kafka and several services running 24 hours a day are expensive for a personal project |
| B: one VM with k3s | Lightweight Kubernetes on a single machine, everything in containers | Low and predictable cost; reuses the manifests from the platform phase | No high availability; you operate the database and Kafka |

## Implementation order

Implementation goes service by service, from the one that depends on nobody to the one that depends on everyone. Each service is built from the inside out and runs on its own before talking to the next one. The tests that validate a layer are written along with it; those that need several services together are left for Phase 9.

**Why this order.** The ledger comes first because it depends on nobody and concentrates the most asked-about topics: modeling, transactions and concurrency. Each following service consumes something the previous one already produces, so integration happens gradually, always with one of the ends already tested.

| Phase | Service | Delivery | How to run it by hand |
| --- | --- | --- | --- |
| 0 | repository | parent pom, `core` compose | `mvn verify`, `docker compose up` |
| 1 | ledger | accounts and internal transfer, synchronous | curl and psql |
| 2 | ledger | events via outbox | Kafka UI |
| 3 | statement | statement in Mongo | curl on the ledger, curl on the statement |
| 4 | ledger | reserve, capture and release commands | manual message through Kafka UI |
| 5 | antifraud | rule-based evaluation | manual message through Kafka UI |
| 6 | spi-simulator and payment | full orchestrated saga | curl on payment, WireMock scenarios |
| 7 | notification | receipt and notice | `awslocal s3 ls`, `awslocal sqs receive-message` |
| 8 | gateway, Keycloak, Consul | single entry point, JWT, discovery | curl with a token |
| 9 | all | system, resilience and load tests, observability | automated suite, k6, Grafana |
| 10 | platform | Jenkins, 3 brokers, kind | pipeline |
| 11 | production | deploy | — |

### Standard structure of a service

Every service has this shape; in the phases below only the classes change.

```
services/ledger-service/
├── pom.xml                          ← packaging pom, lists the 4 modules
├── domain/
│   └── src/main/java/dev/sampay/ledger/domain/
├── application/
│   └── src/main/java/dev/sampay/ledger/application/
│       ├── port/in/                 ← use case interfaces
│       ├── port/out/                ← interfaces that the adapters implement
│       └── service/                 ← use case implementations
├── adapters/
│   └── src/main/
│       ├── java/dev/sampay/ledger/adapters/
│       │   ├── in/rest/             ← controllers, request and response DTOs
│       │   ├── in/kafka/            ← listeners
│       │   ├── out/persistence/     ← JPA entities, repositories, mappers
│       │   └── out/messaging/       ← outbox and relay
│       └── resources/db/migration/  ← Flyway scripts
└── bootstrap/
    └── src/main/
        ├── java/dev/sampay/ledger/  ← LedgerApplication + config/
        └── resources/application.yml
```

Order within each service: **domain → application → outbound adapters → inbound adapters → bootstrap**. Domain and use cases are validated with unit tests, because there is nothing to start yet; from the adapters on, the service runs and is tested by hand.

Ports: gateway 8080, ledger 8081, statement 8082, payment 8083, antifraud 8084, notification 8085, spi-simulator 8090.

### Phase 0: foundation

- Parent `pom.xml` with `packaging pom`, the Spring Boot and Testcontainers BOMs in `dependencyManagement` and `maven.compiler.release=21`.
- `platform/contracts` with only the pom, for now.
- `infra/compose/core.yml`: PostgreSQL only, with a script that creates `ledger_db`. Kafka, MongoDB and the rest come in the phase that needs them.
- **Done when:** `mvn verify` passes and the compose brings everything up healthy.

### Phase 1: synchronous ledger

- **domain:** `Money` (BigDecimal with scale 2, never negative), `AccountId`, `Account` (`deposit`, `debit`, `credit`, `available()`), `LedgerEntry`, `EntryType`, `InternalTransfer`, `InsufficientFundsException`.
- **application:** inbound `OpenAccountUseCase`, `DepositUseCase`, `GetAccountUseCase`, `TransferInternallyUseCase`; outbound `AccountRepository` (with `findByIdForUpdate`), `LedgerEntryRepository`, `InternalTransferRepository`, `UnitOfWork`. Tested with fake in-memory repositories, written by hand.
- **outbound adapters:** JPA entities, Spring Data, mappers, `TransactionTemplateUnitOfWork`, migrations `V1__accounts.sql`, `V2__ledger_entries.sql`, `V3__internal_transfers.sql`.
- **inbound adapters:** `AccountController` (including `POST /accounts/{id}/deposits` to put money into the test accounts), `InternalTransferController`, `ProblemDetailsHandler`.
- **bootstrap:** `LedgerApplication`, `UseCaseConfig`, `application.yml`.
- **By hand:** create two accounts, deposit and transfer with curl; fire two transfers at the same time and check balance and entries in psql; repeat the same `Idempotency-Key`.
- **Done when:** both lock versions exist and the threaded test never leaves a negative balance.

### Phase 2: ledger publishes events

- **contracts:** `Envelope`, `AccountOpened`, `EntriesPosted`.
- **application:** `OutboxPort` port; the Phase 1 use cases start writing the event together with the state.
- **adapters:** `outbox_event` entity and migration, scheduled `OutboxRelay` with `FOR UPDATE SKIP LOCKED` and `KafkaTemplate`.
- **By hand:** transfer and see the message in `ledger.events` in Kafka UI. Stop Kafka, transfer again, see the API respond and the outbox pile up; start Kafka and see the outbox drain.

### Phase 3: statement-service

- **domain:** `StatementEntry`, `AccountSummary`, `StatementCursor`.
- **application:** inbound `ApplyLedgerEventUseCase`, `GetStatementUseCase`, `GetSummaryUseCase`; outbound `StatementRepository`, `SummaryRepository`.
- **adapters:** Mongo documents and index creation; `LedgerEventsListener` with `DefaultErrorHandler` and DLT; `StatementController` with cursor.
- **By hand:** transfer in the ledger and query the statement; resend the same message through Kafka UI and see that nothing is duplicated; publish broken JSON and find it in the DLT.

### Phase 4: ledger receives commands

- **contracts:** `ReserveFunds`, `CaptureFunds`, `ReleaseFunds` and the replies.
- **domain:** `FundsReservation` (HELD → CAPTURED or RELEASED); `Account` gains `reserve`, `capture` and `release`.
- **application:** `ReserveFundsUseCase`, `CaptureFundsUseCase`, `ReleaseFundsUseCase`; `ProcessedMessageRepository` port.
- **adapters:** `LedgerCommandsListener`, `processed_message` table, replies through the outbox to `payment.saga-replies`.
- **By hand:** publish `ReserveFunds` through Kafka UI and see `FundsReserved` and the reserved balance; send the same message twice and check that there is only one reservation.

### Phase 5: antifraud-service

- **domain:** `FraudRule` interface and one class per rule (`MaxAmountRule`, `NightLimitRule`, `VelocityRule`, `BlockedKeyRule`), `FraudPolicy` that combines the rules, `FraudDecision`.
- **application:** `EvaluatePaymentUseCase`; `VelocityCounter` and `BlockedKeys` ports.
- **adapters:** Redis with sorted set; listener with a transactional producer. No REST, only actuator.
- **By hand:** publish `EvaluatePixPayment` with different amounts and see approved and rejected; repeat the same payment and check that the counter does not change.

### Phase 6: spi-simulator and payment-service

- **spi-simulator:** WireMock in the compose, with `POST /settlements`, `GET /settlements/{id}` and slowness and error scenarios.
- **domain:** `PixPayment`, `PaymentStatus` with the allowed transitions (an invalid transition throws an exception), `SagaInstance`, `SettlementResult` as a sealed interface (`Settled`, `Refused`, `Unknown`).
- **application:** inbound `RequestPixPaymentUseCase`, `GetPixPaymentUseCase`, `HandleSagaReplyUseCase`, `SettlePaymentUseCase`, `ExpireSagaStepsUseCase`, `ReconcileSettlementsUseCase`; outbound `PixPaymentRepository`, `SagaRepository`, `OutboxPort`, `SettlementGateway`, `ProcessedMessageRepository`, `UnitOfWork`.
- **adapters:** `PixPaymentController`, `SagaRepliesListener`, persistence and outbox, `SpiSettlementClient` (RestClient + Resilience4j), schedulers for the worker, the deadlines and reconciliation.
- **By hand:** with ledger and antifraud running, create a Pix and follow the states up to `COMPLETED`; turn on the timeout scenario in WireMock and see `SETTLEMENT_UNKNOWN` resolve; turn on the refusal and see the reservation released in the ledger.
- **Done when:** success, refusal and timeout end in a final state.

### Phase 7: notification-service

- **domain:** `Receipt`.
- **application:** `NotifyPaymentResultUseCase`; `ReceiptStorage` and `CustomerNotifier` ports.
- **adapters:** `PaymentEventsListener`; `S3ReceiptStorage` and `SqsCustomerNotifier` with AWS SDK v2.
- **infra:** LocalStack in the compose, with an init script that creates the bucket and the queue.
- **By hand:** complete a Pix, list the receipt with `awslocal s3 ls` and read the notice with `awslocal sqs receive-message`.

### Phase 8: gateway, security and discovery

- Gateway with fixed routes and rate limit in Redis; later, routes through Consul.
- Keycloak with a project realm; gateway and services validate the JWT.
- Services registered in Consul and reading configuration from the KV store.
- **By hand:** request a token from Keycloak and run the whole flow through the gateway; exceed the rate limit and get `429`.

### Phase 9: integration and system tests

- `full` compose with all services.
- Contract tests, end-to-end tests and the resilience suite (invariants 4 to 8) with Toxiproxy.
- OpenTelemetry, dashboards and alerts in Grafana.
- Load with k6: measure the statement, find the bottleneck, apply index and cache, measure again.
- ADRs and records in `docs/experiments`.

### Phases 10 and 11: platform and production

- Jenkinsfile with the full pipeline, Kafka with 3 brokers, manifests for kind.
- Deploy on the route chosen in the infrastructure section.

At the end of each phase we wrap up with the likely interview questions on that topic and a model answer.

## Open decisions

| Decision | Current proposal | When to decide |
| --- | --- | --- |
| Project name | Sampay | Decided: 21 |
| Java 21 or 25 | 21, the version in job postings | Before Phase 0 |
| LocalStack with a free account or Moto | LocalStack | Before Phase 7 |
| Default ledger lock | Decide by the numbers | End of Phase 1 |
| Polling outbox or CDC (Debezium) | Polling | Phase 2; CDC as an experiment later |
| JSON or Avro with Schema Registry | Versioned JSON | Revisit in Phase 9 |
| Production route | A or B, with costs gathered | Phase 11 |
