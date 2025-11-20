-- 1. 칭호(Title) 테이블 데이터
-- [변경] 명칭 수정, 삭제된 칭호 제거, 신규 칭호(출석 단계별, 인생 2회차) 추가
INSERT INTO title (title_id, name, description, created_at, updated_at) VALUES
                                                                            (101, '시드 콜로니', '누적 미션 30회 완료한 자', NOW(), NOW()),
                                                                            (102, '달콤한 첫입', '최초로 모의투자 수익을 실현한 자', NOW(), NOW()),
                                                                            (103, '강형욱', '"잡주(시총 1000억 미만)"로 누적 수익률 100%를 달성한 자', NOW(), NOW()),
                                                                            (104, '카이팅 장인', '하루 동안 매수/매도 50회 이상 달성한 자', NOW(), NOW()),
                                                                            (105, '존 버', '단일 종목 30일 이상 보유하여 수익 실현한 자', NOW(), NOW()),
                                                                            (106, '주식 초보', '7일 연속 출석을 달성한 자', NOW(), NOW()),
                                                                            (107, '주식 중수', '15일 연속 출석을 달성한 자', NOW(), NOW()),
                                                                            (108, '주식 고수', '30일 연속 출석을 달성한 자', NOW(), NOW()),
                                                                            (109, '랭커', '수익률 랭킹 Top 10을 달성한 자', NOW(), NOW()),
                                                                            (111, '인생 2회차', '총 보유 자산이 5만원 미만으로 떨어진 자', NOW(), NOW());

-- 2. 보상(Reward) 테이블 데이터
-- [변경] 트랙 미션 추가에 따른 보상 세분화, 신규 칭호 보상 추가
INSERT INTO reward (reward_id, description, money_amount, title_id, created_at, updated_at) VALUES
-- [일일]
(1, '일일 출석 보상', 10000, NULL, NOW(), NOW()),
(2, '일일 미션 공통 보상', 10000, NULL, NOW(), NOW()),

-- [현실형/단타] (난이도별 금액 상향)
(11, '현실형 중급1(횟수) 보상', 30000, NULL, NOW(), NOW()),
(12, '현실형 중급2(거래금) 보상', 50000, NULL, NOW(), NOW()),
(13, '현실형 고급1(횟수) 보상', 100000, NULL, NOW(), NOW()),
(14, '현실형 고급2(수익) 보상', 200000, NULL, NOW(), NOW()),

-- [탐구형/스윙]
(21, '탐구형 중급1(홀딩) 보상', 50000, NULL, NOW(), NOW()),
(22, '탐구형 중급2(수익) 보상', 80000, NULL, NOW(), NOW()),
(23, '탐구형 고급1(홀딩) 보상', 150000, NULL, NOW(), NOW()),
(24, '탐구형 고급2(수익) 보상', 300000, NULL, NOW(), NOW()),

-- [정투형/장기]
(31, '정투형 중급1(홀딩) 보상', 100000, NULL, NOW(), NOW()),
(32, '정투형 중급2(수익) 보상', 150000, NULL, NOW(), NOW()),
(33, '정투형 고급1(홀딩) 보상', 300000, NULL, NOW(), NOW()),
(34, '정투형 고급2(수익) 보상', 500000, NULL, NOW(), NOW()),

-- [업적/칭호]
(101, '칭호 + 5,000,000원', 5000000, 101, NOW(), NOW()),
(102, '칭호 + 100,000원', 100000, 102, NOW(), NOW()),
(103, '칭호 + 1,000,000원', 1000000, 103, NOW(), NOW()),
(104, '칭호 + 1,500,000원', 1500000, 104, NOW(), NOW()),
(105, '칭호 + 1,500,000원', 1500000, 105, NOW(), NOW()),
(106, '칭호 + 500,000원', 500000, 106, NOW(), NOW()),
(107, '칭호 + 1,000,000원', 1000000, 107, NOW(), NOW()),
(108, '칭호 + 2,000,000원', 2000000, 108, NOW(), NOW()),
(109, '칭호 + 5,000,000원', 5000000, 109, NOW(), NOW()),
(111, '칭호 + 1,000,000원 (구조지원금)', 1000000, 111, NOW(), NOW());

-- 3. 미션(Mission) 테이블 데이터
-- [변경] 트랙별 미션 2개씩 추가 (총 4단계 구조), 업적 미션 변경

-- [일일 미션]
INSERT INTO mission (mission_id, name, track, type, condition_type, goal_value, reward_id, next_mission_id, created_at, updated_at) VALUES
                                                                                                                                        (101, '출석체크하기', 'DAILY', 'COMMON', 'LOGIN_COUNT', 1, 1, NULL, NOW(), NOW()),
                                                                                                                                        (102, '당일 매수/매도 1회 완료하기', 'DAILY', 'COMMON', 'TRADE_COUNT', 1, 2, NULL, NOW(), NOW()),
                                                                                                                                        (103, '종목 리포트 3개 확인하기', 'DAILY', 'COMMON', 'VIEW_REPORT', 3, 2, NULL, NOW(), NOW()),
                                                                                                                                        (104, '포트폴리오 1회 분석하기', 'DAILY', 'COMMON', 'ANALYZE_PORTFOLIO', 1, 2, NULL, NOW(), NOW());

-- [현실형 (단타/SHORT_TERM)]
-- 중급1(10회 거래) -> 중급2(거래액 100만원) -> 고급1(30회 거래) -> 고급2(당일 수익실현 3회)
INSERT INTO mission (mission_id, name, track, type, condition_type, goal_value, reward_id, next_mission_id, created_at, updated_at) VALUES
                                                                                                                                        (201, '당일 매수/매도 합계 10회 달성하기', 'SHORT_TERM', 'INTERMEDIATE', 'TRADE_COUNT', 10, 11, NULL, NOW(), NOW()),
                                                                                                                                        (202, '당일 누적 거래금액 100만원 달성하기', 'SHORT_TERM', 'INTERMEDIATE', 'TOTAL_TRADE_AMOUNT', 1000000, 12, NULL, NOW(), NOW()),
                                                                                                                                        (203, '당일 매수/매도 합계 30회 달성하기', 'SHORT_TERM', 'ADVANCED', 'TRADE_COUNT', 30, 13, NULL, NOW(), NOW()),
                                                                                                                                        (204, '하루에 익절(수익실현) 3번 하기', 'SHORT_TERM', 'ADVANCED', 'DAILY_PROFIT_COUNT', 3, 14, NULL, NOW(), NOW());

-- [탐구형 (스윙/SWING)]
-- 중급1(2일 홀딩) -> 중급2(수익률 5%) -> 고급1(7일 홀딩) -> 고급2(수익률 10%)
INSERT INTO mission (mission_id, name, track, type, condition_type, goal_value, reward_id, next_mission_id, created_at, updated_at) VALUES
                                                                                                                                        (301, '보유 종목 2일 연속 홀딩하기', 'SWING', 'INTERMEDIATE', 'HOLDING_DAYS', 2, 21, NULL, NOW(), NOW()),
                                                                                                                                        (302, '단일 종목 수익률 5% 달성하기', 'SWING', 'INTERMEDIATE', 'PROFIT_RATE', 5, 22, NULL, NOW(), NOW()),
                                                                                                                                        (303, '보유 종목 7일 이상 홀딩하기', 'SWING', 'ADVANCED', 'HOLDING_DAYS', 7, 23, NULL, NOW(), NOW()),
                                                                                                                                        (304, '단일 종목 수익률 10% 달성하기', 'SWING', 'ADVANCED', 'PROFIT_RATE', 10, 24, NULL, NOW(), NOW());

-- [정투형 (장기/LONG_TERM)]
-- 중급1(7일 홀딩) -> 중급2(수익률 10%) -> 고급1(16일 홀딩) -> 고급2(수익률 30%)
INSERT INTO mission (mission_id, name, track, type, condition_type, goal_value, reward_id, next_mission_id, created_at, updated_at) VALUES
                                                                                                                                        (401, '보유 종목 7일 연속 홀딩하기', 'LONG_TERM', 'INTERMEDIATE', 'HOLDING_DAYS', 7, 31, NULL, NOW(), NOW()),
                                                                                                                                        (402, '단일 종목 수익률 10% 달성하기', 'LONG_TERM', 'INTERMEDIATE', 'PROFIT_RATE', 10, 32, NULL, NOW(), NOW()),
                                                                                                                                        (403, '보유 종목 16일 이상 홀딩하기', 'LONG_TERM', 'ADVANCED', 'HOLDING_DAYS', 16, 33, NULL, NOW(), NOW()),
                                                                                                                                        (404, '단일 종목 수익률 30% 달성하기', 'LONG_TERM', 'ADVANCED', 'PROFIT_RATE', 30, 34, NULL, NOW(), NOW());

-- [업적 (ACHIEVEMENT)]
INSERT INTO mission (mission_id, name, track, type, condition_type, goal_value, reward_id, next_mission_id, created_at, updated_at) VALUES
                                                                                                                                        (901, '시드 콜로니', 'ACHIEVEMENT', 'COMMON', 'TOTAL_MISSION_COUNT', 30, 101, NULL, NOW(), NOW()),
                                                                                                                                        (902, '달콤한 첫입', 'ACHIEVEMENT', 'COMMON', 'FIRST_PROFIT', 1, 102, NULL, NOW(), NOW()),
                                                                                                                                        (903, '강형욱', 'ACHIEVEMENT', 'COMMON', 'JUNK_STOCK_JACKPOT', 1, 103, NULL, NOW(), NOW()),
                                                                                                                                        (904, '카이팅 장인', 'ACHIEVEMENT', 'COMMON', 'DAILY_TRADE_COUNT', 50, 104, NULL, NOW(), NOW()),
                                                                                                                                        (905, '존버는 승리한다', 'ACHIEVEMENT', 'COMMON', 'HOLD_FOR_DAYS_AND_SELL_PROFIT', 30, 105, NULL, NOW(), NOW()),
                                                                                                                                        (906, '주식 초보', 'ACHIEVEMENT', 'COMMON', 'LOGIN_STREAK', 7, 106, NULL, NOW(), NOW()),
                                                                                                                                        (907, '주식 중수', 'ACHIEVEMENT', 'COMMON', 'LOGIN_STREAK', 15, 107, NULL, NOW(), NOW()),
                                                                                                                                        (908, '주식 고수', 'ACHIEVEMENT', 'COMMON', 'LOGIN_STREAK', 30, 108, NULL, NOW(), NOW()),
                                                                                                                                        (909, '랭커', 'ACHIEVEMENT', 'COMMON', 'RANKING_TOP_10', 10, 109, NULL, NOW(), NOW()),
                                                                                                                                        (911, '인생 2회차', 'ACHIEVEMENT', 'COMMON', 'ASSET_UNDER_THRESHOLD', 50000, 111, NULL, NOW(), NOW());
-- 4. [필수] 연계 미션(체인) 연결
-- 단타: 201(중1) -> 202(중2) -> 203(고1) -> 204(고2)
UPDATE mission SET next_mission_id = 202 WHERE mission_id = 201;
UPDATE mission SET next_mission_id = 203 WHERE mission_id = 202;
UPDATE mission SET next_mission_id = 204 WHERE mission_id = 203;

-- 스윙: 301(중1) -> 302(중2) -> 303(고1) -> 304(고2)
UPDATE mission SET next_mission_id = 302 WHERE mission_id = 301;
UPDATE mission SET next_mission_id = 303 WHERE mission_id = 302;
UPDATE mission SET next_mission_id = 304 WHERE mission_id = 303;

-- 장기: 401(중1) -> 402(중2) -> 403(고1) -> 404(고2)
UPDATE mission SET next_mission_id = 402 WHERE mission_id = 401;
UPDATE mission SET next_mission_id = 403 WHERE mission_id = 402;
UPDATE mission SET next_mission_id = 404 WHERE mission_id = 403;