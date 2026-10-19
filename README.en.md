# OpsFlow — Incident and Change Management

[简体中文](README.md) | English

OpsFlow is a web application for software operations collaboration. It covers a software service catalog, incident handling, change approval and implementation records, operational reporting, and audit queries. A React frontend connects to a Spring Boot modular monolith backed by PostgreSQL.

## 1. Features

| Module | Implemented capabilities |
| --- | --- |
| Authentication and account | JWT login, current account, session restoration, expiry handling, BCrypt password hashing, and role-based authorization |
| User administration | Create users, paginate accounts, assign roles, enable/disable accounts, and protect the last enabled administrator |
| Software services | Catalog, details, creation, editing, owner selection, enable/disable, and paginated status filtering |
| Incidents | Report, assign/reassign, investigate, append notes, resolve, close, view status history, and filter records |
| Changes | Drafts, links to incidents in the same service, submission, approval/rejection, revision and resubmission, review rounds, and implementation outcomes |
| Failure linkage | A failed change automatically creates an incident for the same service; the result, incident, histories, and audit events commit in one transaction |
| Dashboard | Service/incident/change counts, implementation success rate, seven-day UTC creation trends, status distributions, and recent records |
| Audit logs | Administrator frontend entry, paginated actor/action/entity/time filtering, read-only details, and Chinese/English support |
| User interface | Instant Chinese/English switching, desktop/mobile layouts, loading/empty/error states, pagination, and concurrent-update feedback |

Incident lifecycle: `OPEN → ASSIGNED → INVESTIGATING → RESOLVED → CLOSED`.

Change lifecycle: `DRAFT → PENDING_APPROVAL → APPROVED → IMPLEMENTING → COMPLETED / FAILED`. Rejection produces `REJECTED`; editing returns it to `DRAFT`, and resubmission preserves previous review rounds.

The platform records implementation plans and outcomes; it does not execute deployment, rollback, or operations commands. Email notifications, public registration, password changes, refresh tokens, and server-side logout are not implemented. Logout clears the client session. Incident histories and audit logs cannot be edited or physically deleted through the application.

### Roles and permissions

| Role | Main permissions |
| --- | --- |
| `ADMINISTRATOR` | Manage users/services, assign and close incidents, query business records, maintain and implement changes, and query audit logs |
| `DEVELOPER` | Query business records, report incidents, handle incidents assigned to them, and maintain/implement their own change requests |
| `APPROVER` | Query business records and approve or reject other users' change requests |

Reviewing requires the `APPROVER` role; being an administrator alone does not grant it. Nobody may approve their own request, even with multiple roles. The backend always enforces the final permission and state checks.

## 2. Technology and structure

| Layer | Technology |
| --- | --- |
| Frontend | React 19.3.1, TypeScript 5.9.3, React Router 7.18.4, Vite 6.4.4, Lucide React, and CSS |
| Backend | Java 21, Spring Boot 3.5.16, Spring Web, Spring Data JPA / Hibernate, and Bean Validation |
| Security | Spring Security, OAuth2 Resource Server JWT validation, HS256, and BCrypt; no external OAuth provider required |
| Data | PostgreSQL 18 (18.6 verified locally), Flyway 11.20.3; 11 application tables plus Flyway's migration history table |
| Build | Maven Wrapper 3.9.11, npm, and package-lock.json |
| Testing | JUnit / Spring Boot Test, H2 (tests only), Vitest 4.1.12, and Playwright 1.64.1 / Chromium |
| Health | Spring Boot Actuator health endpoint |

Deployment request path: `Browser (127.0.0.1:8088) → Nginx → Spring Boot (internal port 8080) → PostgreSQL (internal port 5432)`.

```text
OpsFlow/
  README.md                 Chinese project guide
  README.en.md              English project guide
  compose.yaml              Frontend, backend, and database services
  .env.docker.example       Private Docker configuration example
  deploy/                   Initialization script, deployment guide, and local evidence
  backend/                  Spring Boot application and Maven Wrapper
    src/main/java/com/opsflow/  auth, users, services, incidents, changes, dashboard, audit
    src/main/resources/db/migration/  Versioned PostgreSQL migrations
    docs/                   Database and API contracts
    src/test/               HTTP, business-rule, and PostgreSQL tests
  frontend/                 React + TypeScript application
    src/                    Pages, components, API client, and translations
    e2e/                    Browser tests and isolated test backend
    package-lock.json       Frontend dependency lockfile
```

## 3. Docker deployment and access

OpsFlow now runs locally through Docker Compose with three containers: Nginx serves the React build, Spring Boot provides the API, and PostgreSQL stores business data. Open [http://127.0.0.1:8088/](http://127.0.0.1:8088/).

### 3.1 Verified deployment

On 2026-10-18, frontend/backend images were built and containers started. Docker Client and Linux Server were available and all three containers were healthy. Frontend access, API proxying, administrator browser login, audit list/detail, Chinese/English switching, and direct navigation/refresh on seven main pages passed. No JavaScript runtime errors were detected.

These are results of that acceptance run, not a live status report. Local evidence is saved under the Git-ignored `deploy/results/` directory: `DEPLOYMENT-2026-10-18.md`, `docker-runtime.json`, `docker-verification.json`, and bilingual screenshots. Docker previously encountered a Windows Secrets Engine socket startup failure and later became available; its recovery cause and reliability across another restart were not established. GitHub Actions is not configured.

### 3.2 Requirements and isolation

- Windows requires Docker Desktop, WSL 2, and a working Linux engine. `docker version` must show both Client and Server.
- Image builds supply Java, Maven, Node.js, and Nginx. Running this deployment requires no separate host JDK, Node.js, or PostgreSQL installation.
- Only the frontend is published at `127.0.0.1:8088`; backend/database ports remain internal.
- Container PostgreSQL 18.6 uses `opsflow_postgres_data`, mounted at `/var/lib/postgresql`.
- The original Windows development database remains independent and intact. Development accounts and records are not copied automatically.

### 3.3 First build or deployment on another computer

These commands use Windows PowerShell. Git is required to clone the repository. For a first deployment, run every command in order; use section 4 for everyday operation of an existing deployment.

```powershell
git clone https://github.com/j1egeng/OpsFlow.git
cd OpsFlow
docker version
.\deploy\Initialize-Docker.ps1
docker compose --env-file .env.docker -f compose.yaml config --quiet
docker compose --env-file .env.docker -f compose.yaml up -d --build --wait --wait-timeout 240
docker compose --env-file .env.docker -f compose.yaml ps
Invoke-RestMethod 'http://127.0.0.1:8088/actuator/health'
```

If the repository is already cloned, enter its `OpsFlow` root directory on your computer and skip `git clone`. If Docker is absent from PATH, invoke `docker.exe` from your own Docker installation directory by its absolute path.

On macOS/Linux, run `sh ./deploy/init-docker.sh` from the project root to generate private configuration (options: `--admin-username`, `--admin-email`, `--http-port`), then use the same Docker Compose commands. `Invoke-RestMethod` is a PowerShell command; the health endpoint can also be opened in a browser or checked with `curl http://127.0.0.1:8088/actuator/health`.

Initialization creates random database/admin passwords and a stable JWT key only if `.env.docker` does not exist. Existing credentials are preserved. Keep the current private configuration; do not regenerate or commit real credentials. The first build downloads images and Maven/npm dependencies.

Flyway creates tables and roles. Bootstrap creates an administrator only when none is enabled. No manual database creation or host `application-local.yml` setup is required. `config --quiet` only validates configuration; also check container health and working pages/APIs.

### 3.4 Read login credentials locally

Open [http://127.0.0.1:8088/](http://127.0.0.1:8088/) and inspect the private file on your computer:

```powershell
notepad .env.docker
```

Read `BOOTSTRAP_ADMIN_USERNAME` and `BOOTSTRAP_ADMIN_PASSWORD` to sign in. Never share the file, passwords, JWT key, or expanded Compose configuration in chat, screenshots, or Git. Restarting does not overwrite an existing enabled administrator's password.

## 4. Everyday operation and database persistence

### 4.1 Start and inspect

Start Docker Desktop, wait for the Linux engine, and run from the project root:

```powershell
docker compose --env-file .env.docker -f compose.yaml up -d --wait --wait-timeout 240
docker compose --env-file .env.docker -f compose.yaml ps
Invoke-RestMethod 'http://127.0.0.1:8088/actuator/health'
```

All services should be `healthy` and health should return `{"status":"UP"}`. Open port 8088. Everyday startup requires no rebuild or separate Maven/Vite processes.

Inspect application logs:

```powershell
docker compose --env-file .env.docker -f compose.yaml logs --tail 100 backend frontend
```

### 4.2 Stop and update

Before quitting Docker Desktop or shutting down, stop services normally:

```powershell
docker compose --env-file .env.docker -f compose.yaml stop
```

After code changes, rebuild and start:

```powershell
docker compose --env-file .env.docker -f compose.yaml up -d --build --wait --wait-timeout 240
```

Rebuilding images or recreating containers with the same project configuration and named volume preserves the existing database. Flyway applies pending migrations; do not edit migrations already applied.

### 4.3 Does quitting Docker erase the database?

**Normally quitting Docker Desktop, stopping containers, or rebooting does not automatically erase database content.** Committed users, services, incidents, changes, and audit records are stored in `opsflow_postgres_data`, independently of the containers. Services are unavailable while Docker is stopped; starting the same Compose deployment reuses the data.

| Action | Data retention |
| --- | --- |
| Close the Docker Desktop window | Preserved; the engine may keep running in the background |
| Normally quit Docker Desktop, stop containers, or reboot | Preserved; available again after startup |
| `docker compose ... stop` | Containers and named volume preserved |
| `docker compose ... down` without `-v` | Containers/networks removed; this named volume preserved and reused by the next `up` |
| Rebuild images or recreate containers using the same volume | Preserved |
| `down -v`, delete this volume, or run cleanup that removes it | Container database data deleted |
| Factory-reset or clear Docker data | May delete database data; back up first |

Do not casually run `down -v`, remove `opsflow_postgres_data`, or prune volumes. Changing the Compose project/volume name or switching Docker engines can connect to another empty database; that does not establish that the original volume was erased.

Persistence is not a backup. Back up important data separately and stop services normally instead of force-killing processes or cutting power. Preserve `.env.docker`: it stores credentials and the JWT key, but not the business database itself.

See Docker's [volume documentation](https://docs.docker.com/engine/storage/volumes/) and [Compose down documentation](https://docs.docker.com/reference/cli/docker/compose/down/). Further operations are covered in the [Docker deployment guide](deploy/README.md).

## 5. Testing and demonstration

### Automated checks

These development/test commands require a host JDK 21, Node.js 22+, and installed npm dependencies. Everyday Docker operation does not require them. Image builds skip business tests.

Backend (inside `backend`, with Java 21):

```powershell
$env:BOOTSTRAP_ADMIN_ENABLED = 'false'
$env:OPSFLOW_POSTGRES_TESTS = 'false'
.\mvnw.cmd verify
```

Regular tests use in-memory H2; real PostgreSQL tests are skipped by default. Enable `OPSFLOW_POSTGRES_TESTS=true` only when intended, then run `verify`: it reads the `local` configuration, validates/applies migrations, and rolls fixture data back in transactions. Migrations and database sequence advancement are not rolled back; fixture rollback does not mean that absolutely no database state changes.

Frontend (inside `frontend`):

```powershell
npm.cmd run typecheck
npm.cmd test
npm.cmd run build
npx.cmd playwright install chromium
npm.cmd run test:e2e
```

End-to-end tests require JDK 21 and automatically start an isolated H2 backend on 18081, a test frontend on 4173, and Chromium. They do not use local PostgreSQL. Keep both test ports available.

### Solo manual demonstration

1. As administrator, create an enabled software service, a developer account, and a separate approver account.
2. The developer reports an incident; the administrator assigns it; the developer investigates, adds notes, and resolves it; the administrator closes it.
3. The developer drafts a change, completes the reason, risk, future planned time, implementation plan, and rollback plan, then submits it.
4. Sign out and switch to the approver to approve it, then switch back to the developer to start implementation and record an outcome.
5. For failure, inspect the linked incident; for success, verify no failure incident is generated. Refresh the dashboard to check the updated metrics.
6. Check language switching, filters, pagination, required fields, and permission restrictions. Manual trial records persist; use a `TEST-` prefix.

## 6. Troubleshooting and security

| Symptom | What to check |
| --- | --- |
| Backend will not start | Check the Docker Linux engine, container health, backend/db logs, and preserved .env.docker credentials |
| Frontend does not open | Check Docker Desktop, containers, and http://127.0.0.1:8088/ |
| Frontend cannot connect | Check /actuator/health on port 8088, backend/db health, and Nginx logs |
| Login fails | Read credentials locally from .env.docker; the container database uses separate accounts from development |
| Login required after restart | Docker uses a stable JWT key; tokens still expire and closing a tab can clear the session, without erasing database records |
| 403 forbidden | Roles, current assignee/requester, and whether you are attempting self-approval |
| 409 version conflict | Another page changed the record; refresh details and retry instead of resubmitting an old version |
| Change cannot be submitted | All plan fields, a future planned time, an enabled service, and any linked incident belonging to that service |

JWTs use the current tab's `sessionStorage`; only language preference uses `localStorage`. Never put passwords, access tokens, or signing keys in frontend configuration, URLs, or version control.

Docker supplies a stable `JWT_SECRET_BASE64` through private configuration and sets `JWT_REQUIRE_PERSISTENT_KEY=true`. Do not generate a new key on every startup. See the authentication guide for details. Configure CORS using `CORS_ALLOWED_ORIGINS`; the Docker deployment allows local port 8088 origins.

Docker builds exclude private `application-local.*` through `.dockerignore`. For separate host development builds, `.gitignore` does not control Maven packaging and private files may still enter the JAR. Never share artifacts containing credentials.

## 7. Further documentation

The detailed module/API guides below are currently written in Chinese. This English README provides the complete project overview and local setup workflow.

- [Docker deployment, operation, and persistence](deploy/README.md)
- [Backend startup and endpoint index](backend/README.md)
- [Frontend startup and testing](frontend/README.md)
- [Database schema](backend/docs/database.md)
- [Authentication, user management, and administrator bootstrap](backend/docs/auth-users-api.md)
- [Software services API](backend/docs/services-api.md)
- [Incidents API](backend/docs/incidents-api.md)
- [Changes and Dashboard API](backend/docs/changes-dashboard-api.md)
- [Audit logs API](backend/docs/audit-logs-api.md)
