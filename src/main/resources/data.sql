-- 1. 칭호(Title) 테이블 데이터
-- [PostgreSQL Upsert] 칭호 명칭/설명 수정 시 자동 반영
-- 설명: 유저에게 수집 욕구를 자극하는 다양한 칭호 정의
INSERT INTO title (title_id, name, description, created_at, updated_at) VALUES
                                                                            (101, '시드 콜로니', '누적 미션 30회 완료한 자', NOW(), NOW()),
                                                                            (102, '달콤한 첫입', '최초로 모의투자 수익을 실현한 자', NOW(), NOW()),
                                                                            (103, '강형욱', '"잡주(시총 1000억 미만)"로 누적 수익률 100%를 달성한 자', NOW(), NOW()),
                                                                            (104, '카이팅 장인', '하루 동안 매수/매도 50회 이상 달성한 자', NOW(), NOW()),
                                                                            (105, '존 버', '단일 종목 30일 이상 보유하여 수익 실현한 자', NOW(), NOW()),
                                                                            (106, '주식 초보', '7일 연속 출석을 달성한 자', NOW(), NOW()),
                                                                            (107, '주식 중수', '15일 연속 출석을 달성한 자', NOW(), NOW()),
                                                                            (108, '주식 고수', '30일 연속 출석을 달성한 자', NOW(), NOW()),
                                                                            (109, '랭커', '랭킹 Top 10을 달성한 자', NOW(), NOW()),
                                                                            (111, '인생 2회차', '총 보유 자산이 5만원 미만으로 떨어진 자', NOW(), NOW())
ON CONFLICT (title_id)
DO UPDATE SET
    name = EXCLUDED.name,
    description = EXCLUDED.description,
    updated_at = NOW();

-- 2. 보상(Reward) 테이블 데이터
-- [기획] 트랙 난이도에 따른 보상 금액 세분화 (시드머니 100만원 기준)
INSERT INTO reward (reward_id, description, money_amount, title_id, created_at, updated_at) VALUES
-- [일일 미션] 수수료 방어 및 소소한 재미 (시드의 1%)
(1, '일일 출석 보상', 10000, NULL, NOW(), NOW()),
(2, '일일 미션 공통 보상', 10000, NULL, NOW(), NOW()),

-- [현실형/단타] 노동에 대한 대가 (시드의 3% ~ 20%)
(11, '단타형 중급1(횟수) 보상', 30000, NULL, NOW(), NOW()),
(12, '단타형 중급2(거래금) 보상', 50000, NULL, NOW(), NOW()),
(13, '단타형 고급1(횟수) 보상', 100000, NULL, NOW(), NOW()),
(14, '단타형 고급2(수익) 보상', 200000, NULL, NOW(), NOW()),

-- [탐구형/스윙] 분석과 기다림의 밸런스 (시드의 5% ~ 30%)
(21, '스윙형 중급1(홀딩) 보상', 50000, NULL, NOW(), NOW()),
(22, '스윙형 중급2(수익) 보상', 80000, NULL, NOW(), NOW()),
(23, '스윙형 고급1(홀딩) 보상', 150000, NULL, NOW(), NOW()),
(24, '스윙형 고급2(수익) 보상', 300000, NULL, NOW(), NOW()),

-- [정투형/장기] 자금 동결(존버)에 대한 확실한 보상 (시드의 10% ~ 50%)
(31, '장투형 중급1(홀딩) 보상', 100000, NULL, NOW(), NOW()),
(32, '장투형 중급2(수익) 보상', 150000, NULL, NOW(), NOW()),
(33, '장투형 고급1(홀딩) 보상', 300000, NULL, NOW(), NOW()),
(34, '장투형 고급2(수익) 보상', 500000, NULL, NOW(), NOW()),

-- [업적/칭호] 시드 체급 업그레이드 및 명예 보상 (시드의 100% ~ 500%)
(101, '칭호 + 5,000,000원 (시드 콜로니)', 5000000, 101, NOW(), NOW()),
(102, '칭호 + 100,000원 (달콤한 첫입)', 100000, 102, NOW(), NOW()),
(103, '칭호 + 1,000,000원 (강형욱)', 1000000, 103, NOW(), NOW()),
(104, '칭호 + 1,500,000원 (카이팅)', 1500000, 104, NOW(), NOW()),
(105, '칭호 + 1,500,000원 (존버)', 1500000, 105, NOW(), NOW()),
(106, '칭호 + 500,000원 (주식 초보)', 500000, 106, NOW(), NOW()),
(107, '칭호 + 1,000,000원 (주식 중수)', 1000000, 107, NOW(), NOW()),
(108, '칭호 + 2,000,000원 (주식 고수)', 2000000, 108, NOW(), NOW()),
(109, '칭호 + 5,000,000원 (랭커)', 5000000, 109, NOW(), NOW()),
(111, '칭호 + 3,000,000원 (구조지원금)', 3000000, 111, NOW(), NOW()) -- 파산 시 300% 지원
ON CONFLICT (reward_id)
DO UPDATE SET
    description = EXCLUDED.description,
    money_amount = EXCLUDED.money_amount,
    title_id = EXCLUDED.title_id,
    updated_at = NOW();

-- 3. 미션(Mission) 테이블 데이터
-- [기획] 트랙별 4단계 구조 (중급1 -> 중급2 -> 고급1 -> 고급2) 및 조건 정의
INSERT INTO mission (mission_id, name, track, type, condition_type, goal_value, reward_id, next_mission_id, created_at, updated_at) VALUES
-- [일일 미션] 매일 초기화되는 루틴
(101, '출석체크하기', 'DAILY', 'COMMON', 'LOGIN_COUNT', 1, 1, NULL, NOW(), NOW()),
(102, '당일 매수/매도 1회 완료하기', 'DAILY', 'COMMON', 'TRADE_COUNT', 1, 2, NULL, NOW(), NOW()),
(103, '종목 리포트 3개 확인하기', 'DAILY', 'COMMON', 'VIEW_REPORT', 3, 2, NULL, NOW(), NOW()),
(104, '포트폴리오 1회 분석하기', 'DAILY', 'COMMON', 'ANALYZE_PORTFOLIO', 1, 2, NULL, NOW(), NOW()),

-- [현실형 (단타/SHORT_TERM)]
-- 흐름: 10회 거래 -> 100만원 거래 -> 30회 거래 -> 3회 익절
(201, '당일 매수/매도 합계 10회 달성하기', 'SHORT_TERM', 'INTERMEDIATE', 'TRADE_COUNT', 10, 11, NULL, NOW(), NOW()),
(202, '당일 누적 거래금액 100만원 달성하기', 'SHORT_TERM', 'INTERMEDIATE', 'TOTAL_TRADE_AMOUNT', 1000000, 12, NULL, NOW(), NOW()),
(203, '당일 매수/매도 합계 30회 달성하기', 'SHORT_TERM', 'ADVANCED', 'TRADE_COUNT', 30, 13, NULL, NOW(), NOW()),
(204, '하루에 익절(수익실현) 3번 하기', 'SHORT_TERM', 'ADVANCED', 'DAILY_PROFIT_COUNT', 3, 14, NULL, NOW(), NOW()),

-- [탐구형 (스윙/SWING)]
-- 흐름: 2일 홀딩 -> 5% 수익 -> 7일 홀딩 -> 10% 수익
(301, '보유 종목 2일 연속 홀딩하기', 'SWING', 'INTERMEDIATE', 'HOLDING_DAYS', 2, 21, NULL, NOW(), NOW()),
(302, '주식 매도로 수익률 5% 달성하기', 'SWING', 'INTERMEDIATE', 'PROFIT_RATE', 5, 22, NULL, NOW(), NOW()),
(303, '보유 종목 7일 이상 홀딩하기', 'SWING', 'ADVANCED', 'HOLDING_DAYS', 7, 23, NULL, NOW(), NOW()),
(304, '주식 매도로 수익률 10% 달성하기', 'SWING', 'ADVANCED', 'PROFIT_RATE', 10, 24, NULL, NOW(), NOW()),

-- [정투형 (장기/LONG_TERM)]
-- 흐름: 7일 홀딩 -> 10% 수익 -> 16일 홀딩 -> 30% 수익
(401, '보유 종목 7일 연속 홀딩하기', 'LONG_TERM', 'INTERMEDIATE', 'HOLDING_DAYS', 7, 31, NULL, NOW(), NOW()),
(402, '주식 매도로 수익률 10% 달성하기', 'LONG_TERM', 'INTERMEDIATE', 'PROFIT_RATE', 10, 32, NULL, NOW(), NOW()),
(403, '보유 종목 16일 이상 홀딩하기', 'LONG_TERM', 'ADVANCED', 'HOLDING_DAYS', 16, 33, NULL, NOW(), NOW()),
(404, '주식 매도로 수익률 30% 달성하기', 'LONG_TERM', 'ADVANCED', 'PROFIT_RATE', 30, 34, NULL, NOW(), NOW()),

-- [업적 (ACHIEVEMENT)] 영구 달성 및 칭호 획득
(901, '시드 콜로니', 'ACHIEVEMENT', 'COMMON', 'TOTAL_MISSION_COUNT', 30, 101, NULL, NOW(), NOW()),
(902, '달콤한 첫입', 'ACHIEVEMENT', 'COMMON', 'FIRST_PROFIT', 1, 102, NULL, NOW(), NOW()),
(903, '강형욱', 'ACHIEVEMENT', 'COMMON', 'JUNK_STOCK_JACKPOT', 1, 103, NULL, NOW(), NOW()),
(904, '카이팅 장인', 'ACHIEVEMENT', 'COMMON', 'DAILY_TRADE_COUNT', 50, 104, NULL, NOW(), NOW()),
(905, '존버는 승리한다', 'ACHIEVEMENT', 'COMMON', 'HOLD_FOR_DAYS_AND_SELL_PROFIT', 30, 105, NULL, NOW(), NOW()),
(906, '주식 초보', 'ACHIEVEMENT', 'COMMON', 'LOGIN_STREAK', 7, 106, NULL, NOW(), NOW()),
(907, '주식 중수', 'ACHIEVEMENT', 'COMMON', 'LOGIN_STREAK', 15, 107, NULL, NOW(), NOW()),
(908, '주식 고수', 'ACHIEVEMENT', 'COMMON', 'LOGIN_STREAK', 30, 108, NULL, NOW(), NOW()),
(909, '랭커', 'ACHIEVEMENT', 'COMMON', 'RANKING_TOP_10', 10, 109, NULL, NOW(), NOW()),
(911, '인생 2회차', 'ACHIEVEMENT', 'COMMON', 'ASSET_UNDER_THRESHOLD', 50000, 111, NULL, NOW(), NOW())
ON CONFLICT (mission_id)
DO UPDATE SET
    name = EXCLUDED.name,
    track = EXCLUDED.track,
    type = EXCLUDED.type,
    condition_type = EXCLUDED.condition_type,
    goal_value = EXCLUDED.goal_value,
    reward_id = EXCLUDED.reward_id,
    next_mission_id = EXCLUDED.next_mission_id,
    updated_at = NOW();

-- 3-1. [신규] 연속 출석 카운팅용 무한 미션 (ID: 900)
-- 목표값(goal_value)을 100,000으로 설정하여 대시보드 위젯에서 계속 숫자가 올라가게 함
INSERT INTO mission (mission_id, name, track, type, condition_type, goal_value, reward_id, next_mission_id, created_at, updated_at)
VALUES (900, '연속 출석 트래커', 'ACHIEVEMENT', 'COMMON', 'LOGIN_STREAK', 100000, NULL, NULL, NOW(), NOW())
ON CONFLICT (mission_id)
DO UPDATE SET
    name = EXCLUDED.name,
    goal_value = EXCLUDED.goal_value, -- 목표치 수정 시 반영
    next_mission_id = EXCLUDED.next_mission_id,
    updated_at = NOW();

-- 4. 연계 미션(체인) 연결
-- 이전 단계 완료 시 다음 단계 자동 활성화를 위한 체인 구성
UPDATE mission SET next_mission_id = 202 WHERE mission_id = 201;
UPDATE mission SET next_mission_id = 203 WHERE mission_id = 202;
UPDATE mission SET next_mission_id = 204 WHERE mission_id = 203;

UPDATE mission SET next_mission_id = 302 WHERE mission_id = 301;
UPDATE mission SET next_mission_id = 303 WHERE mission_id = 302;
UPDATE mission SET next_mission_id = 304 WHERE mission_id = 303;

UPDATE mission SET next_mission_id = 402 WHERE mission_id = 401;
UPDATE mission SET next_mission_id = 403 WHERE mission_id = 402;
UPDATE mission SET next_mission_id = 404 WHERE mission_id = 403;