#!/usr/bin/env bash
# 측정 전 선행 확인. ops 서버의 저장소 루트에서 실행한다.
#
#   ./benchmark/check.sh
#
# reset.sh 와 같은 방식으로 .env 에서 접속 정보를 읽는다. psql 이 필요하다.
#   sudo apt-get install -y postgresql-client
set -euo pipefail

ENV_FILE="${ENV_FILE:-.env}"
[ -f "$ENV_FILE" ] || { echo "$ENV_FILE 없음. ops 서버의 저장소 루트에서 실행할 것"; exit 1; }

get() { grep "^$1=" "$ENV_FILE" | cut -d= -f2-; }

RDS_HOST=$(get RDS_HOST)
APP_HOST=$(get APP_HOST)
PGDATABASE=$(get POSTGRES_DB)
PGUSER=$(get POSTGRES_USER)
export PGPASSWORD=$(get POSTGRES_PASSWORD)

# 로컬 .env 로 잘못 돌리는 실수를 여기서 끊는다. 로컬에는 RDS_HOST 가 없다.
[ -n "$RDS_HOST" ] || { echo "RDS_HOST 가 비었다. ops 서버의 .env 인지 확인할 것"; exit 1; }

q() { psql -h "$RDS_HOST" -U "$PGUSER" -d "$PGDATABASE" -v ON_ERROR_STOP=1 -t -A -c "$1"; }

echo "== 접속 =="
echo "  RDS $RDS_HOST / db $PGDATABASE / user $PGUSER"
echo "  APP $APP_HOST"

echo
echo "== ① 종목 마스터 =="
stocks=$(q "SELECT count(*) FROM stock WHERE deleted_at IS NULL;")
echo "  종목 $stocks 개"
[ "$stocks" -gt 0 ] || echo "  ⚠ 비었다. /api/batch-jobs/update-master-files 를 먼저 호출할 것"

echo
echo "== ② 오더북 인덱스 =="
idx=$(q "SELECT count(*) FROM pg_indexes WHERE indexname = 'idx_orderbook_active';")
if [ "$idx" -gt 0 ]; then
  echo "  idx_orderbook_active 있음"
else
  echo "  ⚠ 없다. 아래로 적용할 것"
  echo "    psql -h $RDS_HOST -U $PGUSER -d $PGDATABASE -f src/main/resources/db/migration/V10__add_orderbook_index.sql"
fi

echo
echo "== ③ 정산 테이블 =="
tbl=$(q "SELECT count(*) FROM information_schema.tables WHERE table_name = 'settlement';")
if [ "$tbl" -gt 0 ]; then
  echo "  settlement 있음 (ddl-auto: update 가 만든다)"
else
  echo "  ⚠ 없다. 앱이 staging 프로파일로 떴는지 확인할 것"
fi

echo
echo "== ④ 부하 계정 =="
accounts=$(q "SELECT count(*) FROM member WHERE email LIKE 'loadtest%@stockit.local';")
echo "  loadtest 계정 $accounts 개"

echo
echo "== ⑤ 앱 응답 =="
# 액추에이터는 8081(관리 포트)이다. 8080 은 부하 진입점만 받는다.
if curl -sf -m 5 "http://$APP_HOST:8081/actuator/health" > /dev/null; then
  echo "  관리 http://$APP_HOST:8081 OK  (부하 진입점은 8080)"
else
  echo "  ⚠ 앱이 응답하지 않는다"
fi

echo
echo "== ⑥ 직전 회차 잔여 =="
q "SELECT 'trade_order ' || count(*) FROM trade_order
   UNION ALL SELECT 'execution   ' || count(*) FROM execution
   UNION ALL SELECT 'settlement  ' || count(*) FROM settlement;" | sed 's/^/  /'
echo "  (0 이 아니면 reset.sh 를 돌릴 것)"

echo
echo "== ⑦ RDS 왕복 =="
# 왜 매번 재는가: 인스턴스를 껐다 켜면 같은 AZ 안에서도 다른 물리 호스트에 배치되고,
# 그때마다 앱↔RDS 왕복이 0.2~0.5ms 사이에서 달라진다. 사설 IP 는 그대로라 겉으로는
# 아무것도 안 바뀐 것처럼 보인다.
#
# 이 값이 상한을 직접 깎는다. 체결 트랜잭션은 락을 쥔 채 DB 에 약 4.3회 다녀오므로
# 왕복 0.28ms 차이가 임계 구역 1.2ms 로 증폭되고, 상한이 280 → 210/s 로 움직였다
# (2026-09-21 측정). 두 세션의 값으로 회귀하면
#
#     락 보유 = 2.73ms + 4.27 × 왕복
#
# 이라 왕복을 알면 배치에 안 흔들리는 W(순수 작업 시간)를 뽑을 수 있다.
# 디스크를 안 건드리는 SELECT 1 로 재야 커밋 비용이 안 섞인다.
RTT_SQL=$(mktemp)
{ echo "BEGIN;"; seq 1000 | sed 's/.*/SELECT 1;/'; echo "COMMIT;"; } > "$RTT_SQL"
rtt_t0=$(date +%s%N)
psql -h "$RDS_HOST" -U "$PGUSER" -d "$PGDATABASE" -q -f "$RTT_SQL" > /dev/null
rtt_t1=$(date +%s%N)
rm -f "$RTT_SQL"

awk -v a="$rtt_t0" -v b="$rtt_t1" 'BEGIN {
    rtt = (b - a) / 1e6 / 1000
    printf "  %.3f ms/왕복   (임계 구역 기여 %.2f ms = 4.27 × 왕복)\n", rtt, rtt * 4.27
    printf "  회차 기록에 적을 것.  W = 1÷상한(ms) − %.2f\n", rtt * 4.27
}'
