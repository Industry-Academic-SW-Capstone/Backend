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
# 이미 있으면 400 이 오는데 정상 흐름이므로 무시한다.
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
  code=$(curl -s -o /dev/null -w '%{http_code}' \
    -X POST "$BASE_URL/api/members/signup" \
    -H 'Content-Type: application/json' \
    -d "{\"email\":\"$email\",\"password\":\"$PASSWORD\"}")

  case "$code" in
    200|201) created=$((created + 1)) ;;
    400)     existing=$((existing + 1)) ;;
    *)       echo "가입 실패 $email → HTTP $code"; exit 1 ;;
  esac

  if [ $((i % 20)) -eq 0 ]; then echo "  진행: $i/$N"; fi
done

echo "생성 $created / 기존 $existing"
echo "다음: ./benchmark/prepare-stocks.sh <종목수>  (이 계좌들에 보유분을 준다)"
