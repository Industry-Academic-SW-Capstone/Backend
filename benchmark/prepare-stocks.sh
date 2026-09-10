#!/usr/bin/env bash
# 종목 수 스윕(시나리오 B)에 쓸 종목을 준비한다.
#
#   ./benchmark/prepare-stocks.sh 50
#
# 두 가지를 한다.
#   1. 시딩 계정에 해당 종목 보유분을 넣는다 — 매도 시딩이 account_stock을 요구하므로
#   2. k6가 읽을 목록 파일을 만든다 — SQL과 스크립트의 종목이 어긋나지 않게
#
# 종목 선택은 stock_code 오름차순으로 고정한다. 회차마다 같은 종목이어야
# 비교가 성립한다(거래대금 상위 같은 기준은 §1 목표선이 나온 뒤에 쓴다).
set -euo pipefail

N="${1:-50}"
ENV_FILE="${ENV_FILE:-.env}"
OUT="k6/scripts/data/stocks.json"

[ -f "$ENV_FILE" ] || { echo "$ENV_FILE 없음. 저장소 루트에서 실행할 것"; exit 1; }
get() { grep "^$1=" "$ENV_FILE" | cut -d= -f2-; }

RDS_HOST=$(get RDS_HOST); PGDATABASE=$(get POSTGRES_DB); PGUSER=$(get POSTGRES_USER)
export PGPASSWORD=$(get POSTGRES_PASSWORD)
PSQL="psql -h $RDS_HOST -U $PGUSER -d $PGDATABASE -v ON_ERROR_STOP=1"

echo "== 상위 $N 종목에 보유분 부여 =="
$PSQL <<SQL
INSERT INTO account_stock (account_id, stock_code, quantity, hold_quantity, average_price, created_at, updated_at)
SELECT a.account_id, s.stock_code, 1000000000, 0, 100, now(), now()
FROM account a
CROSS JOIN (SELECT stock_code FROM stock WHERE deleted_at IS NULL ORDER BY stock_code LIMIT $N) s
ON CONFLICT (account_id, stock_code) DO UPDATE
  SET quantity = EXCLUDED.quantity, hold_quantity = 0, updated_at = now();
SQL

echo "== 목록 파일 생성: $OUT =="
mkdir -p "$(dirname "$OUT")"
$PSQL -t -A -c "SELECT stock_code FROM stock WHERE deleted_at IS NULL ORDER BY stock_code LIMIT $N" \
  | awk 'BEGIN{printf "["} {printf "%s\"%s\"", (NR>1?",":""), $0} END{print "]"}' > "$OUT"

echo "종목 수: $(tr -cd ',' < "$OUT" | wc -c | awk '{print $1+1}')"
head -c 200 "$OUT"; echo
