# AGENTS.md

This file provides guidance to AI coding agents (Claude Code, Cursor, Copilot, etc.) when working with code in this repository. It is the vendor-neutral source of truth; tool-specific files (e.g. `CLAUDE.md`) just point here.

## Project Overview

stockIt is a Korean stock trading simulation platform built with Spring Boot. It integrates with Korea Investment Securities (KIS) API for real-time stock data, supports WebSocket-based live order books, and includes features like contests, missions, rankings, and AI-powered stock analysis (Gemini).

## Detailed Docs (read on demand)

This file is the always-loaded orientation. For deep dives, read the relevant `docs/` file only when needed:

- `docs/architecture.md` — package/domain structure, event flow, caching
- `docs/matching-engine.md` — Redis order-book matching internals (order book keys, distributed lock, pessimistic account lock, dual-write consistency, recovery batch, concurrency)
- `docs/websocket.md` — real-time streaming design (STOMP config, subscription reference-counting, KIS WebSocket client, broadcast flow)
- `docs/deployment.md` — CI/CD pipeline, Docker Hub, SSH + `docker-compose.prod.yml`, secrets, monitoring
- `docs/development.md` — local setup, profiles, testing, migrations
- `docs/conventions.md` — **read before writing code**: naming rules (DTO suffixes, records), length guidelines, Spring/JPA rules, Checkstyle/ArchUnit/JaCoCo usage and baselines

## Build & Run Commands

```bash
# Build
./gradlew build              # Full build with tests
./gradlew build -x test      # Build without tests
./gradlew clean build        # Clean build

# Test
./gradlew test                                    # Run all tests
./gradlew test --tests "ClassName"                 # Run specific test class
./gradlew test --tests "ClassName.methodName"      # Run specific test method

# Docker (development)
docker-compose up -d          # Start all services (Traefik, PostgreSQL, Redis, Backend)
docker-compose down           # Stop all services
docker-compose build          # Rebuild backend image

# Load testing (k6)
docker-compose --profile load-test run --rm k6 run /scripts/matching-engine-test.js
```

## Architecture

**Java 21 / Spring Boot 3.5.6 / Gradle** with PostgreSQL 15, Redis 7, Flyway migrations.

### Package Structure (`grit.stockIt`)

- **`domain/`** — Business modules, each with controller/service/repository/dto/entity layers:
  - `matching` — Redis-based limit order matching engine (core trading logic)
  - `order` / `execution` — Order placement and execution tracking with event-driven processing (`LimitOrderFillEvent`)
  - `account` — Portfolio and cash management
  - `stock` — Stock data and KIS API integration
  - `contest` — Trading competitions
  - `ranking` — Performance rankings (Caffeine-cached, 60s TTL)
  - `member` / `auth` — User accounts, Kakao OAuth, local auth
  - `notification` — FCM push notifications
  - `llm` — Gemini AI company analysis
  - `mission` / `title` / `industry` — Gamification and classification

- **`global/`** — Cross-cutting infrastructure:
  - `config/` — Security, WebSocket (STOMP), Redis, JPA, Cache, Flyway, Swagger, external API properties
  - `jwt/` — JWT filter and token service
  - `exception/` — GlobalExceptionHandler with custom exceptions (BadRequestException, ForbiddenException, UntradeableStockException)
  - `websocket/` — KIS WebSocket client, STOMP broker, subscription management

- **`job/`** — Batch operations (KIS master file download/parsing)

### Key Architectural Patterns

- **Event-driven**: Domain events (e.g., `LimitOrderFillEvent`) decouple order matching from execution/notification
- **3-tier caching**: Redis (distributed) + Caffeine (local rankings) + `@Cacheable`
- **WebSocket**: STOMP over SockJS at `/ws`, topics at `/topic`, `/queue`; app prefix `/app`
- **JSON convention**: Jackson SNAKE_CASE — Java camelCase auto-converts to snake_case in API responses
- **Monitoring**: Spring Actuator + Micrometer → Prometheus metrics; PostgreSQL slow-query monitoring via `pg_stat_statements` (see `monitoring/`)

## Environment & Profiles

Three Spring profiles: `local` (localhost DB/Redis), `dev` (Docker services, **default**), `prod` (external DB). Active profile defaults to `dev` via `SPRING_PROFILES_ACTIVE`.

Required env vars (configured in `.env`):
- `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_URL`
- `KIS_API_APPKEY`, `KIS_API_APPSECRET`
- `KAKAO_REST_API_KEY`, `KAKAO_REDIRECT_URI`
- `JWT_SECRET`, `GEMINI_API_KEY`, `CLAUDE_API_KEY`, `DART_API_KEY`, `PYTHON_ANALYSIS_URL`

> Prod-only secrets (e.g. `KAKAO_CLIENT_SECRET`, `FIREBASE_CREDENTIALS_BASE64`) are injected via GitHub Actions Secrets and Docker Compose environment variables at deploy time, not the local `.env`. See `docs/deployment.md`.

## Testing

- **Unit/Integration**: JUnit 5 + Testcontainers (PostgreSQL) — no external DB needed
- **Concurrency tests**: `LimitOrderMatchingServiceConcurrencyTest` validates data consistency under concurrent order matching
- **Load tests**: k6 scripts in `/k6/scripts/` (order API, execution, integrated workflow)

## Database Migrations

Flyway SQL scripts in `src/main/resources/db/migration/` (V2, V6–V9). Flyway auto-run is disabled; initialization is handled by `FlywayConfig.java`.

## CI/CD

GitHub Actions (`.github/workflows/ci-cd.yml`): builds with JDK 21 Temurin, pushes a Docker image to `${DOCKER_USERNAME}/stockit` (tags: branch ref, `<branch>-<sha>`, and `latest` on the default branch), deploys via SSH. CD triggers on push to `main`.

## Code Conventions

Full rules live in `docs/conventions.md` — **read it before writing or renaming code**. Violations fail the build (Checkstyle + ArchUnit run in `./gradlew build`). Highlights:

- **Lombok**: `@Getter`, `@Builder`, `@NoArgsConstructor(access = PROTECTED)`, `@Slf4j`; constructor injection only (`@RequiredArgsConstructor`, no field `@Autowired`); no `@Setter` on entities
- **DTOs**: `{Action}Request` / `{Domain}Response` in `dto/` packages; prefer Java records; never use a `Dto` suffix. Renaming DTO **fields** changes the API contract (global SNAKE_CASE Jackson) — class renames are safe, field renames need frontend coordination
- **Architecture**: controllers must not reference repositories directly (go through services)
- **REST paths**: `/api/{domain}` (e.g., `/api/members`, `/api/orders`)
- **Validation**: JSR-303 `@Valid` on request bodies
- **API docs**: SpringDoc OpenAPI annotations (`@Operation`, `@Tag`) — Swagger UI at `/swagger-ui/`
- **Baselines**: pre-existing violations are suppressed (`config/checkstyle/suppressions.xml`, `src/test/resources/archunit_store/`) — never add new entries to them
