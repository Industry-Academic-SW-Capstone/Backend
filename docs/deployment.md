# 배포 런북

stockIt는 **GitHub Actions로 Docker 이미지를 빌드·푸시한 뒤, SSH로 서버에 접속해
`docker-compose.prod.yml`로 실행**하는 방식으로 배포됩니다. (Helm/Kubernetes는 사용하지 않습니다.)

파이프라인 정의: `.github/workflows/ci-cd.yml`

## 파이프라인 개요

```
push → CI (모든 브랜치)                CD (main 브랜치만)
 ├─ JDK 21(Temurin) 빌드          ┌─ Docker 이미지 빌드
 └─ 테스트                         ├─ Docker Hub push (${DOCKER_USERNAME}/stockit)
                                   ├─ SCP: 설정 파일 서버 전송
                                   └─ SSH: env export → docker compose up
```

- **트리거**: `main` 브랜치 push 시 CD 실행.
- **이미지**: `${DOCKER_USERNAME}/stockit`
  - 태그: 브랜치명(`type=ref`), `<branch>-<sha>`, 기본 브랜치에서는 `latest`.

## CD 단계 상세

1. **빌드 & 푸시** — `docker/build-push-action`으로 이미지 빌드 후 Docker Hub에 push.
   빌드 캐시는 GitHub Actions 캐시(`type=gha`) 사용.
2. **설정 파일 전송 (SCP)** — 다음 파일을 서버로 복사 (`overwrite: true`):
   - `traefik/traefik.yml`, `traefik/dynamic_conf.yml`
   - `docker-compose.prod.yml`
   - `monitoring/prometheus/prometheus.yml`
   - `monitoring/grafana/provisioning/**`, `monitoring/grafana/dashboards/*.json`
   - `monitoring/postgres/init-pg-stat-statements.sql`
3. **배포 (SSH)** — 서버에서:
   - `traefik/letsencrypt/acme.json` 생성·권한 설정(최초 배포용), Redis 로그 디렉토리 준비
   - GitHub Secrets를 **환경변수로 export** (`SPRING_PROFILES_ACTIVE=prod`)
   - `docker compose -f docker-compose.prod.yml down && pull && up -d`
   - 24시간 지난 오래된 이미지 정리

## 시크릿 관리

시크릿은 **GitHub Actions Secrets**에 저장되고, 배포 시 서버 환경변수로 주입됩니다.
저장소나 이미지에 시크릿을 커밋하지 않습니다.

필요한 시크릿:

| 분류 | 시크릿 |
|------|--------|
| Docker Hub | `DOCKER_USERNAME`, `DOCKER_PASSWORD` |
| 서버 접속 | `SERVER_HOST`, `SERVER_USERNAME`, `SERVER_SSH_KEY` |
| DB | `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_URL` |
| Redis | `SPRING_REDIS_HOST`, `SPRING_REDIS_PORT` |
| KIS | `KIS_API_APPKEY`, `KIS_API_APPSECRET` |
| Kakao | `KAKAO_REST_API_KEY`, `KAKAO_CLIENT_SECRET`, `KAKAO_REDIRECT_URI` |
| 기타 | `JWT_SECRET`, `FIREBASE_CREDENTIALS_BASE64`, `GEMINI_API_KEY`, `PYTHON_ANALYSIS_URL` |
| 시큐리티 | `APP_CORS_ALLOWED_ORIGINS`, `APP_ADMIN_EMAILS` |

> `APP_CORS_ALLOWED_ORIGINS`는 배포 필수입니다. 와일드카드 오리진을 더 이상 허용하지
> 않으므로(자격증명 동반 요청과 함께 쓸 수 없음) 도메인을 명시해야 하며, **미설정 시
> 애플리케이션이 기동에 실패합니다(fail-fast)**. 빈 목록은 모든 오리진 차단이라 조용히
> 뜨면 원인 추적이 어려워 의도적으로 기동을 막습니다. 값은 스킴 포함·끝 슬래시 없이
> 작성합니다(`https://foo.com` ⭕ / `https://foo.com/` ❌).
>
> `APP_ADMIN_EMAILS`가 비면 관리자 API에 아무도 접근할 수 없습니다(fail-closed).
> **허용목록에 넣는 주소는 반드시 이미 가입된 계정이어야 합니다** — 회원가입에 이메일
> 소유 검증이 없어(`LocalMemberService.signup`은 중복만 검사), 아직 가입하지 않은 주소를
> 넣으면 그 주소로 먼저 가입한 사람이 관리자 권한을 갖습니다. 관리자를 추가할 때는
> 해당 계정의 가입 여부를 먼저 확인하세요. 근본 해법은 `Member.role` 승격입니다(#212).

> `CLAUDE_API_KEY`, `DART_API_KEY`는 로컬 개발 전용이라 운영 배포 시크릿에는 포함되지 않습니다
> (CI 배포 단계에서 주입하지 않음). 위 목록은 실제 `ci-cd.yml`이 주입하는 시크릿과 일치합니다.

## Compose 구성

- 개발: `docker-compose.yml` — Traefik, PostgreSQL(`db`), Redis, Backend, k6, 모니터링(prometheus/grafana/exporters)
- 스테이징: `docker-compose.staging.yml`
- 운영: `docker-compose.prod.yml`

## 모니터링

- **Prometheus** — 애플리케이션은 Actuator + Micrometer로 `/actuator/prometheus` 메트릭 노출.
- **Grafana** — 대시보드: `spring-boot-dashboard.json`, `postgresql-dashboard.json` (프로비저닝 자동 로드).
- **PostgreSQL 슬로우 쿼리** — `pg_stat_statements` (init SQL: `monitoring/postgres/init-pg-stat-statements.sql`).
- 관련 설정은 `monitoring/` 디렉토리에 있으며 배포 시 서버로 SCP 됩니다.

## 리버스 프록시

Traefik이 라우팅·TLS(Let's Encrypt)를 담당합니다. 인증서는 서버의
`traefik/letsencrypt/acme.json`에 저장됩니다 (권한 600).
