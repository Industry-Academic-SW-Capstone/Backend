-- 1. 칭호(Title) 테이블 데이터 (업적용 칭호 10개)
-- (ID, 이름, 설명, 생성일, 수정일)
INSERT INTO title (title_id, name, description, created_at, updated_at) VALUES
                                                                            (101, '주식의 신', '모든 성향별 미션을 완료한 자', NOW(), NOW()),
                                                                            (102, '첫 수익', '최초로 모의투자 수익을 실현한 자', NOW(), NOW()),
                                                                            (103, '강형욱', '"잡주"로 누적 수익률 100%를 달성한 자', NOW(), NOW()),
                                                                            (104, '스캘퍼', '하루 동안 매수/매도 50회 이상 달성한 자', NOW(), NOW()),
                                                                            (105, '존 버', '단일 종목 30일 이상 보유하여 수익 실현한 자', NOW(), NOW()),
                                                                            (106, '가치투자자', '단일 종목으로 100%(2배) 이상 수익률을 달성한 자', NOW(), NOW()),
                                                                            (107, '은행장', '금융 섹터 주식 5종목 이상 동시 보유한 자', NOW(), NOW()),
                                                                            (108, '개근상', '30일 연속 출석을 달성한 자', NOW(), NOW()),
                                                                            (109, '랭커', '수익률 랭킹 Top 10을 달성한 자', NOW(), NOW()),
                                                                            (110, '따상!', '신규 상장 주식으로 당일 100% 이상 수익 실현한 자', NOW(), NOW());

-- 2. 보상(Reward) 테이블 데이터 (미션 + 업적 보상)
-- (ID, 설명, 보상금액, 칭호ID, 생성일, 수정일)
INSERT INTO reward (reward_id, description, money_amount, title_id, created_at, updated_at) VALUES
                                                                                                (1, '일일 출석 보상', 10000, NULL, NOW(), NOW()),
                                                                                                (2, '일일 미션 공통 보상', 10000, NULL, NOW(), NOW()),
                                                                                                (11, '현실형 중급(단타) 보상', 30000, NULL, NOW(), NOW()),
                                                                                                (12, '현실형 고급(단타) 보상', 70000, NULL, NOW(), NOW()),
                                                                                                (13, '탐구형 중급(스윙) 보상', 100000, NULL, NOW(), NOW()),
                                                                                                (14, '탐구형 고급(스윙) 보상', 200000, NULL, NOW(), NOW()),
                                                                                                (15, '정투형 중급(장기) 보상', 150000, NULL, NOW(), NOW()),
                                                                                                (16, '정투형 고급(장기) 보상', 400000, NULL, NOW(), NOW()),
                                                                                                (101, '칭호 + 5,000,000원', 5000000, 101, NOW(), NOW()),
                                                                                                (102, '칭호 + 100,000원', 100000, 102, NOW(), NOW()),
                                                                                                (103, '칭호 + 1,000,000원', 1000000, 103, NOW(), NOW()),
                                                                                                (104, '칭호 + 1,500,000원', 1500000, 104, NOW(), NOW()),
                                                                                                (105, '칭호 + 1,500,000원', 1500000, 105, NOW(), NOW()),
                                                                                                (106, '칭호 + 3,000,000원', 3000000, 106, NOW(), NOW()),
                                                                                                (107, '칭호 + 150,000원', 150000, 107, NOW(), NOW()),
                                                                                                (108, '칭호 + 1,500,000원', 1500000, 108, NOW(), NOW()),
                                                                                                (109, '칭호 + 5,000,000원', 5000000, 109, NOW(), NOW()),
                                                                                                (110, '칭호 + 1,000,000원', 1000000, 110, NOW(), NOW());

-- 3. 미션(Mission) 테이블 데이터 (next_mission_id는 NULL로 먼저 INSERT)

-- 일일 미션 (교양 트랙)
INSERT INTO mission (mission_id, name, track, type, condition_type, goal_value, reward_id, next_mission_id, created_at, updated_at) VALUES
                                                                                                                                        (101, '출석체크하기', 'DAILY', 'COMMON', 'LOGIN_COUNT', 1, 1, NULL, NOW(), NOW()),
                                                                                                                                        (102, '당일 매수/매도 1회 완료하기', 'DAILY', 'COMMON', 'TRADE_COUNT', 1, 2, NULL, NOW(), NOW()),
                                                                                                                                        (103, '종목 리포트 3개 확인하기', 'DAILY', 'COMMON', 'VIEW_REPORT', 3, 2, NULL, NOW(), NOW()),
                                                                                                                                        (104, '포트폴리오 1회 분석하기', 'DAILY', 'COMMON', 'ANALYZE_PORTFOLIO', 1, 2, NULL, NOW(), NOW());

-- 현실형 (단타/SHORT_TERM)
INSERT INTO mission (mission_id, name, track, type, condition_type, goal_value, reward_id, next_mission_id, created_at, updated_at) VALUES
                                                                                                                                        (201, '당일 매수/매도 합계 10회 달성하기', 'SHORT_TERM', 'INTERMEDIATE', 'TRADE_COUNT', 10, 11, NULL, NOW(), NOW()),
                                                                                                                                        (202, '당일 매수/매도 합계 30회 달성하기', 'SHORT_TERM', 'ADVANCED', 'TRADE_COUNT', 30, 12, NULL, NOW(), NOW());

-- 탐구형 (스윙/SWING)
INSERT INTO mission (mission_id, name, track, type, condition_type, goal_value, reward_id, next_mission_id, created_at, updated_at) VALUES
                                                                                                                                        (301, '보유 종목 2일 연속 홀딩하기(매도)', 'SWING', 'INTERMEDIATE', 'HOLDING_DAYS', 2, 13, NULL, NOW(), NOW()),
                                                                                                                                        (302, '보유 종목 7일 이상 홀딩하기(매도)', 'SWING', 'ADVANCED', 'HOLDING_DAYS', 7, 14, NULL, NOW(), NOW());

-- 정투형 (장기/LONG_TERM)
INSERT INTO mission (mission_id, name, track, type, condition_type, goal_value, reward_id, next_mission_id, created_at, updated_at) VALUES
                                                                                                                                        (401, '보유 종목 7일 연속 홀딩하기(매도)', 'LONG_TERM', 'INTERMEDIATE', 'HOLDING_DAYS', 7, 15, NULL, NOW(), NOW()),
                                                                                                                                        (402, '보유 종목 16일 이상 홀딩하기(매도)', 'LONG_TERM', 'ADVANCED', 'HOLDING_DAYS', 16, 16, NULL, NOW(), NOW());

-- 업적 (ACHIEVEMENT)
INSERT INTO mission (mission_id, name, track, type, condition_type, goal_value, reward_id, next_mission_id, created_at, updated_at) VALUES
                                                                                                                                        (901, '주식의 신', 'ACHIEVEMENT', 'COMMON', 'COMPLETE_ALL_TRACKS', 3, 101, NULL, NOW(), NOW()),
                                                                                                                                        (902, '첫 수익의 기쁨', 'ACHIEVEMENT', 'COMMON', 'FIRST_PROFIT', 1, 102, NULL, NOW(), NOW()),
                                                                                                                                        (903, '잡주계의 강형욱', 'ACHIEVEMENT', 'COMMON', 'PROFIT_RATE_ON_STOCK_TYPE', 100, 103, NULL, NOW(), NOW()),
                                                                                                                                        (904, '단타의 신', 'ACHIEVEMENT', 'COMMON', 'DAILY_TRADE_COUNT', 50, 104, NULL, NOW(), NOW()),
                                                                                                                                        (905, '존버는 승리한다', 'ACHIEVEMENT', 'COMMON', 'HOLD_FOR_DAYS_AND_SELL_PROFIT', 30, 105, NULL, NOW(), NOW()),
                                                                                                                                        (906, '리틀 버핏', 'ACHIEVEMENT', 'COMMON', 'PROFIT_RATE_100', 100, 106, NULL, NOW(), NOW()),
                                                                                                                                        (907, '은행장', 'ACHIEVEMENT', 'COMMON', 'HOLD_SECTOR_STOCKS', 5, 107, NULL, NOW(), NOW()),
                                                                                                                                        (908, '주식 중독', 'ACHIEVEMENT', 'COMMON', 'LOGIN_STREAK', 30, 108, NULL, NOW(), NOW()),
                                                                                                                                        (909, '랭커', 'ACHIEVEMENT', 'COMMON', 'RANKING_TOP_10', 10, 109, NULL, NOW(), NOW()),
                                                                                                                                        (910, '따상!', 'ACHIEVEMENT', 'COMMON', 'IPO_PROFIT_RATE_100', 100, 110, NULL, NOW(), NOW());


-- 4. [필수] 모든 INSERT가 끝난 후, UPDATE로 연계 미션 연결
UPDATE mission SET next_mission_id = 202 WHERE mission_id = 201;
UPDATE mission SET next_mission_id = 302 WHERE mission_id = 301;
UPDATE mission SET next_mission_id = 402 WHERE mission_id = 401;