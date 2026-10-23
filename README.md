# Sampay

A digital wallet with Pix, built as a study project in distributed backend systems: Java microservices,
Kafka, an orchestrated saga, PostgreSQL and MongoDB. The full architecture is in
[docs/architecture.md](docs/architecture.md).

Current state: **Phases 0 and 1**: monorepo, local PostgreSQL and a synchronous `ledger-service`.

## Prerequisites

- Java 21 (Temurin). With SDKMAN, run `sdk env` in the repository root to use the version pinned in `.sdkmanrc`.
- No local Maven needed: `./mvnw` downloads the pinned version (3.10.0) on first use.
- Docker with Compose v2. On Windows: Docker Desktop with **WSL integration** turned on for the distro
  (Settings → Resources → WSL integration). Without it, `docker` does not exist inside WSL and the bind mount
  of `init.sql` fails.

## Start the infrastructure

```bash
docker compose -f infra/compose/core.yml up -d
docker compose -f infra/compose/core.yml ps        # postgres should show as (healthy)
docker exec sampay-postgres psql -U sampay -d sampay -c '\l ledger_db'
```

PostgreSQL 18.6 on `localhost:5432`, user and password `sampay`. `ledger_db` is created on the first
start, while the volume is empty.

To stop it and keep the data: `docker compose -f infra/compose/core.yml down` (`-v` deletes the volume).

## Build

```bash
./mvnw verify                                             # everything
./mvnw -pl services/ledger-service/bootstrap -am verify   # only the ledger and what it needs
```

## Run the ledger

With the infrastructure up and the build done, from the repository root:

```bash
java -jar services/ledger-service/bootstrap/target/ledger-bootstrap-0.1.0-SNAPSHOT.jar
curl localhost:8081/actuator/health                       # {"groups":["liveness","readiness"],"status":"UP"}
```
