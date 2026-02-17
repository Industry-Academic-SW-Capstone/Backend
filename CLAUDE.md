# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

stockIt is a Korean stock trading simulation platform built with Spring Boot. It integrates with Korea Investment Securities (KIS) API for real-time stock data, supports WebSocket-based live order books, and includes features like contests, missions, rankings, and AI-powered stock analysis (Gemini).

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

## Environment & Profiles

Three Spring profiles: `local` (localhost DB/Redis), `dev` (Docker services), `prod` (external DB).

Required env vars (configured in `.env`):
- `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_URL`
- `KIS_API_APPKEY`, `KIS_API_APPSECRET`
- `KAKAO_REST_API_KEY`, `KAKAO_CLIENT_SECRET`, `KAKAO_REDIRECT_URI`
- `JWT_SECRET`, `FIREBASE_CREDENTIALS_BASE64`, `GEMINI_API_KEY`, `PYTHON_ANALYSIS_URL`

## Testing

- **Unit/Integration**: JUnit 5 + Testcontainers (PostgreSQL) — no external DB needed
- **Concurrency tests**: `LimitOrderMatchingServiceConcurrencyTest` validates data consistency under concurrent order matching
- **Load tests**: k6 scripts in `/k6/scripts/` (order API, execution, integrated workflow)

## Database Migrations

Flyway SQL scripts in `src/main/resources/db/migration/` (V2–V9). Flyway auto-run is disabled; initialization is handled by `FlywayConfig.java`.

## CI/CD

GitHub Actions (`.github/workflows/ci-cd.yml`): builds with JDK 21 Temurin, pushes Docker image to `hwnahee/stockit:main`, deploys via SSH. CD triggers on push to `main`.

## Code Conventions

- **Lombok**: `@Getter`, `@Builder`, `@NoArgsConstructor(access = PROTECTED)`, `@Slf4j`
- **DTOs**: `{Action}Request` / `{Domain}Response` in `dto/` packages
- **REST paths**: `/api/{domain}` (e.g., `/api/members`, `/api/orders`)
- **Validation**: JSR-303 `@Valid` on request bodies
- **API docs**: SpringDoc OpenAPI annotations (`@Operation`, `@Tag`) — Swagger UI at `/swagger-ui/`
