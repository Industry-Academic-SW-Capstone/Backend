#!/usr/bin/env bash
# 종목 수 스윕(시나리오 B)에 쓸 부하 계정을 만든다.
#
#   ./benchmark/prepare-accounts.sh 50
#
# 왜 필요한가: 정산은 findByIdWithLock 으로 계좌 행을 잠근다. 주문이 전부 한 계좌 것이면
# 종목을 N개로 늘려도 정산 N건이 같은 행 앞에 줄을 서서, 종목 병렬이 계좌 락에 막힌다.
# 그 상태로 재면 "종목을 늘려도 안 늘어난다"가 나오는데 설계 한계가 아니라 픽스처 탓이다.
#
# 회원가입이 기본 계좌를 함께 만든다(MemberRegistrationService#register).
#
# ★ 중복 가입을 상태코드로 판정하지 않는다. LocalAuthService 는 IllegalArgumentException 을
#   던지는데 GlobalExceptionHandler 에 전용 핸들러가 없어 handleAll 로 떨어져 500 이 된다.
#   "이미 있음"과 진짜 장애가 같은 코드라 구분이 안 되므로, 먼저 /api/members/exists 로
#   확인하고 없을 때만 가입한다. 이 경로는 PUBLIC_PATHS 라 토큰이 필요 없다.
#
# ★ 실행 순서. prepare-stocks.sh 가 CROSS JOIN account 로 "그 시점에 있는" 계좌에만
#   보유분을 주므로 반드시 이 스크립트가 먼저다.
#
#     ./benchmark/prepare-accounts.sh 50     ← 계좌 M개
#     ./benchmark/prepare-stocks.sh 50       ← 종목 N개 × 전 계좌 보유분
#     ./benchmark/reset.sh
#     ... run /scripts/b-parallelism.js --env STOCK_COUNT=50 --env ACCOUNT_COUNT=50
set -euo pipefail

N="${1:-50}"
ENV_FILE="${ENV_FILE:-.env}"
[ -f "$ENV_FILE" ] || { echo "$ENV_FILE 없음. 저장소 루트에서 실행할 것"; exit 1; }
get() { grep "^$1=" "$ENV_FILE" | cut -d= -f2-; }

# ops 서버에서 도는 스크립트라 앱은 사설 IP 로 찾아간다. reset.sh · prepare-stocks.sh 와
# 같은 방식으로 .env 의 APP_HOST 를 읽는다.
BASE_URL="${BASE_URL:-http://$(get APP_HOST):8080}"
PREFIX="${LOAD_EMAIL_PREFIX:-loadtest}"
DOMAIN="${LOAD_EMAIL_DOMAIN:-stockit.local}"
PASSWORD="${LOAD_PASSWORD:-loadtest1234}"

echo "== 부하 계정 $N 개 생성 ($BASE_URL) =="

created=0
existing=0
for i in $(seq 1 "$N"); do
  email="${PREFIX}${i}@${DOMAIN}"

  if curl -sf -m 10 "$BASE_URL/api/members/exists?email=$email" | grep -q '"exists":true'; then
    existing=$((existing + 1))
  else
    body=$(curl -s -m 30 -w '\n%{http_code}' \
      -X POST "$BASE_URL/api/members/signup" \
      -H 'Content-Type: application/json' \
      -d "{\"email\":\"$email\",\"password\":\"$PASSWORD\"}")
    code=$(printf '%s' "$body" | tail -n1)

    if [ "$code" = "200" ] || [ "$code" = "201" ]; then
      created=$((created + 1))
    else
      echo "가입 실패 $email → HTTP $code"
      printf '%s\n' "$body" | head -n -1
      exit 1
    fi
  fi

  if [ $((i % 20)) -eq 0 ]; then echo "  진행: $i/$N"; fi
done

echo "생성 $created / 기존 $existing"
echo "다음: ./benchmark/prepare-stocks.sh <종목수>  (이 계좌들에 보유분을 준다)"
