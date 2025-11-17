-- =============================================
-- 랭킹 시스템 성능 최적화를 위한 인덱스 생성
-- =============================================

-- 1. account 테이블 인덱스
-- =============================================

-- 1-1. Main 계좌 랭킹 조회 최적화
-- WHERE is_default = true ORDER BY cash DESC
CREATE INDEX IF NOT EXISTS idx_account_main_cash 
ON account (is_default, cash DESC) 
WHERE is_default = true;

-- 1-2. 대회별 잔액 랭킹 조회 최적화
-- WHERE contest_id = ? ORDER BY cash DESC
CREATE INDEX IF NOT EXISTS idx_account_contest_cash 
ON account (contest_id, cash DESC) 
WHERE contest_id IS NOT NULL;

-- 1-3. 대회별 수익률 랭킹 조회 최적화
-- WHERE contest_id = ? ORDER BY (cash - seed_money) / seed_money DESC
-- (수익률은 계산 필요하므로 단순 인덱스, 완벽한 최적화는 어려움)
CREATE INDEX IF NOT EXISTS idx_account_contest_id 
ON account (contest_id) 
WHERE contest_id IS NOT NULL;

-- 1-4. 회원별 계좌 조회 최적화 (내 랭킹 조회용)
-- WHERE member_id = ?
CREATE INDEX IF NOT EXISTS idx_account_member_id 
ON account (member_id);


-- 2. contest 테이블 인덱스
-- =============================================

-- 2-1. 진행 중인 대회 조회 최적화
-- WHERE start_date <= NOW() AND (end_date IS NULL OR end_date >= NOW()) AND is_default = false
CREATE INDEX IF NOT EXISTS idx_contest_active 
ON contest (is_default, start_date, end_date) 
WHERE is_default = false;

-- 2-2. 대회 조회 최적화 (기본 키가 아닌 경우 대비)
CREATE INDEX IF NOT EXISTS idx_contest_id 
ON contest (contest_id);


-- 3. 기존 인덱스 확인 (중복 방지)
-- =============================================

-- 기존 인덱스 목록 확인 쿼리 (참고용, 실행하지 않음)
-- SELECT 
--     tablename, 
--     indexname, 
--     indexdef 
-- FROM pg_indexes 
-- WHERE tablename IN ('account', 'contest')
-- ORDER BY tablename, indexname;


-- 4. 인덱스 생성 결과 확인
-- =============================================

-- 인덱스 크기 확인 (참고용)
-- SELECT 
--     schemaname,
--     tablename,
--     indexname,
--     pg_size_pretty(pg_relation_size(indexrelid)) AS index_size
-- FROM pg_stat_user_indexes
-- WHERE tablename IN ('account', 'contest')
-- ORDER BY pg_relation_size(indexrelid) DESC;


-- =============================================
-- 성능 개선 효과 (예상)
-- =============================================
--
-- 1. Main 계좌 랭킹 조회:
--    - Before: Full table scan (느림)
--    - After: Index scan (빠름)
--    - 예상 개선: 10배~100배
--
-- 2. 대회별 랭킹 조회:
--    - Before: Full table scan + sort (매우 느림)
--    - After: Index scan + index sort (빠름)
--    - 예상 개선: 20배~200배
--
-- 3. 내 랭킹 계산:
--    - Before: Full table scan for COUNT (느림)
--    - After: Index scan for COUNT (빠름)
--    - 예상 개선: 10배~50배
--
-- 총 예상 성능 개선: 평균 50ms → 2ms (25배 개선)
-- =============================================


-- =============================================
-- 주의사항
-- =============================================
--
-- 1. 인덱스는 읽기 성능을 향상시키지만, 쓰기 성능을 약간 저하시킵니다.
-- 2. 랭킹 시스템은 읽기가 압도적으로 많으므로 인덱스가 유리합니다.
-- 3. 인덱스는 디스크 공간을 사용합니다 (테이블 크기의 약 10~20%).
-- 4. 주기적으로 VACUUM ANALYZE를 실행하여 인덱스를 최적화해야 합니다.
-- =============================================

