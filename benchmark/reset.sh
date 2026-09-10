#!/usr/bin/env bash
# 측정 회차 사이 초기화. ops 서버의 저장소 루트에서 실행한다.
#
#   ./benchmark/reset.sh
#
# .env에서 접속 정보를 읽는다. psql과 redis-cli가 필요하다.
#   sudo apt-get install -y postgresql-client redis-tools
set -euo pipefail

ENV_FILE="${ENV_FILE:-.env}"
[ -f "$ENV_FILE" ] || { echo "$ENV_FILE 없음. 저장소 루트에서 실행할 것"; exit 1; }

get() { grep "^$1=" "$ENV_FILE" | cut -d= -f2-; }

RDS_HOST=$(get RDS_HOST)
REDIS_HOST=$(get REDIS_HOST)
PGDATABASE=$(get POSTGRES_DB)
PGUSER=$(get POSTGRES_USER)
export PGPASSWORD=$(get POSTGRES_PASSWORD)

echo "== DB 초기화 =="
psql -h "$RDS_HOST" -U "$PGUSER" -d "$PGDATABASE" -v ON_ERROR_STOP=1 -f benchmark/reset.sql

echo "== Redis 초기화 =="
# 오더북·주문 캐시만 지운다. FLUSHALL은 다른 캐시까지 날려 원인 추적을 어렵게 한다.
redis-cli -h "$REDIS_HOST" --scan --pattern 'sim:order:*' | while read -r k; do
  [ -n "$k" ] && redis-cli -h "$REDIS_HOST" del "$k" > /dev/null
done
echo "남은 sim:order:* 키: $(redis-cli -h "$REDIS_HOST" --scan --pattern 'sim:order:*' | wc -l)"

echo "== pg_stat_statements 초기화 =="
# 회차별 쿼리 통계를 깨끗이 보려면 여기서 리셋한다.
psql -h "$RDS_HOST" -U "$PGUSER" -d "$PGDATABASE" -c "SELECT pg_stat_statements_reset();" > /dev/null
echo "완료"
