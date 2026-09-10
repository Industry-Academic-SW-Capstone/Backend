-- 벤치마크: 인덱스 제거 (Seq Scan 상태로 측정)
--
-- 블로그 1편의 전제("RDB는 매 체결마다 정렬 쿼리가 실행된다")를 재현하는 조건이다.
-- 이 상태의 수치는 "RDB의 성능"이 아니라 "인덱스 없는 RDB의 성능"으로만 서술해야 한다.
--
-- 실행 (ops 서버, 저장소 루트에서):
--   export PGPASSWORD=$(grep '^POSTGRES_PASSWORD' .env | cut -d= -f2-)
--   psql -h "$RDS_HOST" -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f benchmark/index-off.sql
--
-- 회차 초기화는 benchmark/reset.sh 가 함께 처리한다.

DROP INDEX IF EXISTS idx_orderbook_active;
DROP INDEX IF EXISTS idx_matching_event_queue;

-- 확인: 아래 결과에 두 인덱스가 없어야 한다.
SELECT tablename, indexname
FROM pg_indexes
WHERE tablename IN ('trade_order', 'matching_event_queue')
ORDER BY tablename, indexname;

-- 두 인덱스 모두 마이그레이션(V10·V11)에서만 생성되고 엔티티에는 선언돼 있지 않다.
-- Flyway는 이미 적용 기록을 갖고 있으므로 앱을 재시작해도 되살아나지 않는다.
-- 즉 DROP 상태가 그대로 유지된다.
