# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

Apache Fineract — an open-source core banking platform (REST API only, no UI). This repo is a fork (`komchey24/fineract`) of `apache/fineract`; the primary integration branch is `develop`. Java 21, Spring Boot, Gradle multi-module build. PostgreSQL is the recommended database (MySQL/MariaDB are deprecated).

The Angular frontend (Mifos web-app fork) that consumes this API lives locally at `/Users/chanhengseang/Documents/repo/web-app-kc24`.

Before reporting security findings, consult `SECURITY.md` for the threat model and known non-findings (see `AGENTS.md`).

## Common Commands

```bash
# Create databases (PostgreSQL on localhost:5432, user root/postgres)
./gradlew createPGDB -PdbName=fineract_tenants
./gradlew createPGDB -PdbName=fineract_default

# Run the server for development (skips checkstyle/spotless/spotbugs/javadoc)
./gradlew devRun
# API then listens at https://localhost:8443/fineract-provider (self-signed cert)
# Health: curl --insecure https://localhost:8443/fineract-provider/actuator/health
# Default credentials: mifos/password; tenant header: Fineract-Platform-TenantId: default

# Unit tests (fast, no external services; ~1000 tests)
./gradlew test -x :twofactor-tests:test -x :oauth2-tests:test -x :integration-tests:test

# Single unit test class
./gradlew :fineract-loan:test --tests "fully.qualified.ClassName"

# Single integration test (integration-tests deploys the WAR into Tomcat via Cargo on :8443;
# pass -PcargoDisabled if a server is already running)
./gradlew :integration-tests:test --tests "org.apache.fineract.integrationtests.SomeTest" -PdbType=postgresql

# Cucumber E2E tests (require a running server: ./gradlew bootRun -Dspring.profiles.active=test)
cd fineract-e2e-tests-runner
INITIALIZATION_ENABLED=true ../gradlew cucumber          # first run on a fresh DB (seeds data)
../gradlew cucumber -Pcucumber.features="src/test/resources/features/Loan.feature"

# Format / lint (build fails on violations; run before committing)
./gradlew spotlessApply
./gradlew checkstyleMain checkstyleTest

# Build the executable JAR (output: fineract-provider/build/libs)
./gradlew clean bootJar

# Build Docker image
./gradlew :fineract-provider:jibDockerBuild -x test
```

Configuration is env-var driven via `fineract-provider/src/main/resources/application.properties` (`${ENV_VAR:default}` pattern, e.g. `FINERACT_DEFAULT_TENANTDB_*`, `FINERACT_HIKARI_*`).

## Architecture

### Module layering

- **fineract-core** — foundational library nearly every module depends on: multi-tenancy infrastructure, the command (CQRS) framework, business-event bus, shared domain (organisation, portfolio commons, useradministration).
- **Feature modules** — `fineract-loan`, `fineract-savings`, `fineract-accounting`, `fineract-charge`, `fineract-tax`, `fineract-rates`, `fineract-investor`, `fineract-progressive-loan`, `fineract-working-capital-loan`, `fineract-loan-origination`, `fineract-branch`, `fineract-document`, `fineract-report`, etc. Each plugs into core.
- **fineract-provider** — the Spring Boot application and REST API layer aggregating all modules. Main class: `fineract-provider/src/main/java/org/apache/fineract/ServerApplication.java`.
- **fineract-cob** — Close of Business day-end batch framework (Spring Batch). Pluggable ordered steps implement `COBBusinessStep`; supports remote partitioning across worker nodes via ActiveMQ/Kafka.
- **fineract-command\*** — newer command-processing infrastructure (jdbc/audit/async/disruptor variants).
- **fineract-client / fineract-client-feign / fineract-avro-schemas** — published SDK artifacts (built against Java 8).
- **fineract-war** — WAR packaging, used by the Cargo-based integration tests.
- Test modules: `integration-tests`, `twofactor-tests`, `oauth2-tests`, `fineract-e2e-tests-core` + `fineract-e2e-tests-runner` (Cucumber).

### Command pattern (write path)

All write operations flow through a command pipeline in `org.apache.fineract.commands` (fineract-core):

1. A REST API resource builds a `CommandWrapper` via `CommandWrapperBuilder` (entity, action, JSON body).
2. It calls `PortfolioCommandSourceWritePlatformService`, which delegates to `SynchronousCommandProcessingService` (handles maker-checker approval and audit).
3. `CommandHandlerProvider` looks up the handler bean annotated `@CommandType(entity = "X", action = "Y")` implementing `NewCommandSourceHandler`, which invokes the domain write service.

When adding a new write operation you typically touch: API resource, `CommandWrapperBuilder`, a new `@CommandType` handler, the write platform service, and a permission row in a Liquibase migration.

### Multi-tenancy

Every request carries a `Fineract-Platform-TenantId` header, read by `TenantAwareBasicAuthenticationFilter` (fineract-security). Tenant details come from the shared `fineract_tenants` DB, are stored in `ThreadLocalContextUtil`, and `RoutingDataSource` (fineract-core, `infrastructure/core/service/database`) routes queries to that tenant's own database. Two schemas exist: the tenant-store DB and per-tenant DBs.

### Database migrations (Liquibase)

- Master changelog: `fineract-provider/src/main/resources/db/changelog/db.changelog-master.xml`, split into `tenant-store/` and `tenant/` trees (contexts `tenant_store_db` / `tenant_db`).
- Each feature module ships its own changelog under `<module>/src/main/resources/db/changelog/tenant/module/<name>/module-changelog-master.xml` with numbered files in `parts/`.
- To add a migration: create the next-numbered XML in the module's `parts/` folder and `<include>` it in that module's `module-changelog-master.xml`. CI enforces backward compatibility and DDL safety on changelogs.

### Business events

Internal: producers call `BusinessEventNotifierService.notifyPre/PostBusinessEvent`; consumers implement `BusinessEventListener` (fineract-core, `infrastructure/event/business`). External events (for third-party systems) are persisted by `ExternalEventService`, serialized with Avro (`fineract-avro-schemas`), and published to ActiveMQ or Kafka; disabled by default (`fineract.events.external.enabled`).

### Custom modules

`custom/<company>/<category>/<module>` directories are auto-discovered by `settings.gradle` (any dir containing a `build.gradle`). The pattern (see `custom/acme/`): a `starter/` sub-module with a Spring Boot auto-configuration wires beans from sibling implementation modules (`service/`, `processor/`, `job/`, `cob/`, `externalevent/`). `custom/docker` builds a `fineract-custom` image bundling all custom modules; custom Liquibase changelogs go in `db/custom-changelog` (context `custom_changelog`).

## Code Conventions

Enforced by Checkstyle (`config/checkstyle/checkstyle.xml`) and Spotless (`config/fineractdev-formatter.xml`) during every build — run `./gradlew spotlessApply` to auto-fix.

- **Lombok**: `@Getter`/`@Setter`/`@NoArgsConstructor` on JPA entities — never `@Data` on entities (breaks JPA lazy loading). `@RequiredArgsConstructor` for constructor injection on services, `@Slf4j` for logging, `@Data` only for simple DTOs. Never `@SneakyThrows`.
- **Exceptions**: rethrow or log with the root cause; no empty catch blocks; never catch `NullPointerException`. In tests, declare `throws` and let exceptions propagate (use `assertThrows` when testing for failure).
- **Logging**: SLF4J with `{}` placeholders, never string concatenation or `System.out`/`printStackTrace()`. API validation errors are signaled via the response, not `LOG.error`.

## Pull Requests

- PR title: `FINERACT-<issue>: <Imperative one-liner>` (JIRA issue id first).
- Commits must be GPG-signed (`./scripts/verify-signed-commits.sh` to check).
- Maintainers merge with merge commits (no squash/rebase merges); contributors may rebase/force-push their branches freely.
