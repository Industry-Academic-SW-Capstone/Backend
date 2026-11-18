# 배포 런북 (토스 배포 · `toss/main`)

EC2 한 대(메모리 2GB + 스왑 2GB)에 `docker-compose.prod.yml` 로 **Traefik + PostgreSQL(pgvector) + 백엔드 + AI 서버**를 띄운다.
GitHub Actions 가 이미지를 Docker Hub 에 올리고, SSH 로 서버에서 `docker compose up -d` 한다.

```
toss/main push ─ CI(빌드·테스트) ─ CD ┬─ 이미지 push: ${DOCKER_USERNAME}/stockit:toss (+ toss-<sha>)
                                      ├─ SCP: docker-compose.prod.yml, traefik/traefik.yml, postgres/init/
                                      └─ SSH: ~/stockit 에서 pull backend → up -d
AI 저장소 toss/main push ─ 이미지 push: stockit-ai:toss ─ SSH: pull ai → up -d ai
```

- 파이프라인: `.github/workflows/ci-cd.yml` (AI 는 AI 저장소의 같은 파일)
- 기존 운영(`main`)과 서버·시크릿을 공유하지 않는다. 시크릿 이름이 `TOSS_*` 다.
- 비밀값은 GitHub 이 아니라 **서버의 `~/stockit/.env` 하나**에 둔다. CI 는 비밀값을 모른다.

## 메모리 예산

| 컨테이너 | 상한 | 실측(가벼운 부하) |
|---|---|---|
| traefik | 96MB | 약 55MB |
| postgres (pgvector) | 384MB | 약 65MB |
| backend | 896MB (힙 약 490MB) | 약 400~620MB |
| ai | 320MB | 약 210~240MB |

합계 약 1.7GB. 나머지는 OS 몫이고 순간 초과는 스왑이 받는다. 메모리가 모자라면 컨테이너 OOM 재시작이 먼저 보인다
(`docker ps` 의 재시작 횟수, `docker inspect -f '{{.State.OOMKilled}}' backend`).

## 서버 준비 (한 번)

1. **보안 그룹**: 인바운드 22(관리자 IP 만), 80, 443. DB·AI·백엔드 포트는 열지 않는다(컨테이너 네트워크 안에서만 통신).
2. **Docker**: Docker Engine + Compose 플러그인 설치, 배포 사용자를 `docker` 그룹에 넣는다.
3. **스왑 2GB**:
   ```bash
   sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile
   echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
   echo 'vm.swappiness=10' | sudo tee /etc/sysctl.d/99-swap.conf && sudo sysctl --system
   ```
   `swappiness=10` 은 평소엔 램을 쓰고 모자랄 때만 스왑으로 넘기게 한다.
4. **DNS**: `API_DOMAIN` 의 A 레코드를 서버 IP 로. Traefik 이 80 포트 HTTP 챌린지로 Let's Encrypt 인증서를 받는다.
5. **`.env`**: `mkdir ~/stockit` 후 `.env.prod.example` 을 `~/stockit/.env` 로 복사해 채운다. `chmod 600 .env`.

## GitHub 시크릿

| 저장소 | 시크릿 |
|---|---|
| 백엔드·AI 공통 | `DOCKER_USERNAME`, `DOCKER_PASSWORD`, `TOSS_SERVER_HOST`, `TOSS_SERVER_USERNAME`, `TOSS_SERVER_SSH_KEY` |

## 첫 배포 순서

1. AI 저장소 `toss/main` 을 push(또는 Actions 수동 실행) → `stockit-ai:toss` 이미지가 생긴다. 스택이 없으니 이미지만 올리고 끝난다.
2. `.env` 에 `APP_RUN_MASTER_UPDATE_ON_STARTUP=true` 를 두고 백엔드 `toss/main` 을 push → 스택이 처음 뜬다.
   - PostgreSQL 첫 기동 때 `postgres/init/10-news-db.sh` 가 뉴스 DB(`stock_news`)·계정(`stockai`)·vector 확장을 만든다
   - 백엔드가 테이블(엔티티), V2·V10 인덱스, 미션·칭호 기초 데이터를 만들고(`application-prod.yml`), KIS 종목 마스터를 받는다
   - AI 서버는 첫 리포트 요청 때 뉴스 테이블을 만든다
3. 종목이 들어왔는지 확인한 뒤 `.env` 를 `APP_RUN_MASTER_UPDATE_ON_STARTUP=false` 로 되돌리고 `docker compose -f docker-compose.prod.yml up -d backend`.
   이후 종목 갱신은 관리자 API `POST /api/batch-jobs/update-master-files` 로 한다.

> `POSTGRES_*`·`NEWS_DB_PASSWORD` 는 **데이터 디렉터리가 빈 첫 기동에만** 쓰인다. 나중에 `.env` 만 바꾸면 접속이 깨진다.
> 바꾸려면 DB 안에서 `ALTER ROLE ... PASSWORD` 를 먼저 하고 `.env` 를 맞춘다.

## 이후 배포

`toss/main` 에 push 하면 끝난다. 백엔드 컨테이너만 새로 만들어지고, Traefik 은 healthy 판정(약 10초) 뒤부터 새 컨테이너로 보낸다.
서버가 한 대라 그 사이 요청은 실패한다.

배포 스크립트는 **볼륨을 지우지 않는다**(`docker system prune --volumes` 금지). 새 컨테이너가 뜨지 못한 상태에서 지우면 DB 가 사라진다.

## 백업

관리형 DB 가 아니라 백업은 직접 한다. `pg_dumpall` 은 계정과 두 DB(`$POSTGRES_DB`, `stock_news`)를 함께 덤프한다.

```bash
# crontab -e  — 매일 04:00, 7일치 보관
0 4 * * * cd ~/stockit && docker exec postgres pg_dumpall -U $(grep ^POSTGRES_USER= .env | cut -d= -f2) | gzip > backups/db-$(date +\%F).sql.gz && find backups -name 'db-*.sql.gz' -mtime +7 -delete
```

`mkdir ~/stockit/backups` 가 먼저 필요하다. 서버가 통째로 사라지는 경우에 대비하려면 덤프를 S3 같은 서버 밖 저장소에도 올린다.

## 환경변수 주의

- `APP_CORS_ALLOWED_ORIGINS` 는 필수다. 와일드카드 오리진을 허용하지 않으므로 도메인을 명시해야 하고, **미설정 시 기동에 실패한다(fail-fast)**.
  스킴 포함·끝 슬래시 없이 쓴다(`https://foo.com` ⭕ / `https://foo.com/` ❌).
- `APP_ADMIN_EMAILS` 가 비면 관리자 API 에 아무도 접근할 수 없다(fail-closed).
  **허용목록에 넣는 주소는 반드시 이미 가입된 계정이어야 한다** — 회원가입에 이메일 소유 검증이 없어(`LocalMemberService.signup` 은 중복만 검사),
  아직 가입하지 않은 주소를 넣으면 그 주소로 먼저 가입한 사람이 관리자 권한을 갖는다.
- `FIREBASE_CREDENTIALS_BASE64` 가 비면 FCM 푸시만 꺼지고 나머지는 동작한다.
- `NAVER_CLIENT_ID/SECRET` 이 비면 AI 리포트가 뉴스 없이 만들어진다.

## 리버스 프록시

Traefik 이 TLS 와 라우팅을 맡는다. 라우팅은 백엔드 컨테이너 라벨(`docker-compose.prod.yml`)이 정하고, 도메인은 `.env` 의 `API_DOMAIN` 이다.
인증서는 서버의 `traefik/letsencrypt/acme.json`(권한 600)에 저장된다. WebSocket(`/ws`)도 같은 라우터로 간다.
