package grit.stockIt.domain.mission.service;

import grit.stockIt.domain.account.entity.Account;
import grit.stockIt.domain.account.entity.AccountStock;
import grit.stockIt.domain.account.repository.AccountRepository;
import grit.stockIt.domain.account.repository.AccountStockRepository; // [추가]
import grit.stockIt.domain.member.entity.Member;
import grit.stockIt.domain.member.repository.MemberRepository;
import grit.stockIt.domain.mission.dto.MemberTitleDto;
import grit.stockIt.domain.mission.dto.MissionDashboardDto;
import grit.stockIt.domain.mission.dto.MissionListDto;
import grit.stockIt.domain.mission.entity.Mission;
import grit.stockIt.domain.mission.entity.MissionProgress;
import grit.stockIt.domain.mission.entity.Reward;
import grit.stockIt.domain.mission.enums.MissionConditionType;
import grit.stockIt.domain.mission.enums.MissionStatus;
import grit.stockIt.domain.mission.enums.MissionTrack;
import grit.stockIt.domain.mission.enums.MissionType;
import grit.stockIt.domain.mission.repository.MissionProgressRepository;
import grit.stockIt.domain.mission.repository.MissionRepository;
import grit.stockIt.domain.order.entity.OrderMethod;
import grit.stockIt.domain.order.event.TradeCompletionEvent;
import grit.stockIt.domain.title.entity.MemberTitle;
import grit.stockIt.domain.title.entity.Title;
import grit.stockIt.domain.title.repository.MemberTitleRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import grit.stockIt.domain.stock.entity.Stock;
import grit.stockIt.domain.stock.repository.StockRepository;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional // 미션 관련 로직은 하나의 트랜잭션으로 관리
public class MissionService {

    // --- 의존성 주입 ---
    private final MemberRepository memberRepository;
    private final MissionRepository missionRepository;
    private final MissionProgressRepository missionProgressRepository;
    private final MemberTitleRepository memberTitleRepository;
    private final AccountRepository accountRepository;
    private final AccountStockRepository accountStockRepository; // [추가됨] 홀딩 여부 확인용
    private final StockRepository stockRepository;

    private static final long JUNK_STOCK_MARKET_CAP_THRESHOLD = 100000000000L;
    /**
     * [1] (이벤트 수신) 거래 이벤트 발생 시 미션 진행도 업데이트
     * - 일반 미션 갱신 로직
     * - 매도(SELL) 발생 시 '홀딩' 미션 초기화 로직 포함
     */
    public void updateMissionProgress(TradeCompletionEvent event) {
        log.info("수신된 거래 이벤트: MemberId={}, Method={}, Qty={}",
                event.getMemberId(), event.getOrderMethod(), event.getFilledQuantity());

        Member member = memberRepository.findById(event.getMemberId())
                .orElseThrow(() -> new EntityNotFoundException("회원을 찾을 수 없습니다."));

        // 1. 회원의 '진행 중'인 미션 목록 조회
        List<MissionProgress> progressList = missionProgressRepository
                .findByMemberAndStatusWithMission(member, MissionStatus.IN_PROGRESS);

        // 2. 진행 중인 미션 순회
        for (MissionProgress progress : progressList) {
            Mission mission = progress.getMission();
            MissionConditionType type = mission.getConditionType();

            // 🚨 [핵심 로직] 매도(SELL) 발생 시 -> '홀딩' 미션은 무조건 0으로 초기화 (존버 실패)
            if (event.getOrderMethod() == OrderMethod.SELL && type == MissionConditionType.HOLDING_DAYS) {
                if (progress.getCurrentValue() > 0) {
                    log.info("매도 발생으로 홀딩 미션 리셋! MissionId={}, 기존값={}",
                            mission.getId(), progress.getCurrentValue());
                    progress.setCurrentValue(0); // 0일차로 초기화
                }
                continue; // 초기화했으니 다른 검사는 건너뜀
            }

            // 3. 그 외 조건 매칭 여부 확인 및 업데이트
            if (isMissionConditionMatches(mission, event)) {
                updateProgressValue(progress, mission, event);
            }
        }
        // 2. [신규] 특수 업적 미션 체크 (달콤한 첫입, 강형욱)
        checkSpecialAchievement(member, event);
    }

    /**
     * [신규] 특수 조건 업적 처리
     * - 달콤한 첫입 (FIRST_PROFIT)
     * - 강형욱 (JUNK_STOCK_JACKPOT)
     */
    private void checkSpecialAchievement(Member member, TradeCompletionEvent event) {
        // 매도가 아니거나 수익이 없으면 패스
        if (event.getOrderMethod() != OrderMethod.SELL) return;

        // 수익 여부 판단 (매도가 > 평단가)
        boolean isProfit = event.getFilledPrice().compareTo(event.getBuyAveragePrice()) > 0;
        if (!isProfit) return;

        // A. 달콤한 첫입 (첫 수익 실현)
        handleOneTimeAchievement(member, MissionConditionType.FIRST_PROFIT, 1);

        // B. 강형욱 (잡주로 100% 이상 수익)
        // 종목 정보 조회
        Stock stock = stockRepository.findById(event.getStockCode()).orElse(null);

/*        // 시가총액 1,000억 미만이고, 수익률이 100% 이상인 경우
        if (stock != null && stock.getMarketCap() < JUNK_STOCK_MARKET_CAP_THRESHOLD) {
            // 수익률 계산: (매도가 - 평단가) / 평단가
            BigDecimal profitRate = event.getFilledPrice().subtract(event.getBuyAveragePrice())
                    .divide(event.getBuyAveragePrice(), 2, java.math.RoundingMode.HALF_UP);

            // 1.0 이상 (100%)
            if (profitRate.compareTo(BigDecimal.ONE) >= 0) {
                log.info("잡주 대박 터짐! Member={}, Stock={}, Rate={}", member.getName(), stock.getName(), profitRate);
                handleOneTimeAchievement(member, MissionConditionType.JUNK_STOCK_JACKPOT, 1);
            }
        }*/
    }

    /**
     * 1회성 업적 달성 처리 헬퍼 (이미 완료되었으면 무시)
     */
    private void handleOneTimeAchievement(Member member, MissionConditionType type, int value) {
        missionProgressRepository.findByMemberAndMissionTypeWithMission(member, MissionTrack.ACHIEVEMENT, type)
                .ifPresent(progress -> {
                    if (!progress.isCompleted()) {
                        progress.setCurrentValue(value);
                        checkMissionCompletion(progress);
                    }
                });
    }
    /**
     * [신규] 스케줄러 호출용: 매일 자정에 보유 주식이 있으면 홀딩 일수 +1
     */
    public void processDailyHoldingUpdate() {
        // 1. 'HOLDING_DAYS' 조건이면서 '진행 중'인 미션들만 조회
        List<MissionProgress> holdingProgressList = missionProgressRepository
                .findAllByMission_ConditionTypeAndStatus(MissionConditionType.HOLDING_DAYS, MissionStatus.IN_PROGRESS);

        for (MissionProgress progress : holdingProgressList) {
            Member member = progress.getMember();

            // 2. 회원이 주식을 하나라도 가지고 있는지 확인 (수량 > 0)
            boolean hasStock = accountStockRepository.existsByAccount_MemberAndQuantityGreaterThan(member, 0);

            if (hasStock) {
                progress.incrementProgress(1);
                log.info("홀딩 미션 +1일 증가: MemberId={}, MissionId={}, NewValue={}",
                        member.getMemberId(), progress.getMission().getId(), progress.getCurrentValue());
                checkMissionCompletion(progress);
            }
        }
    }

    // [수정] 진행도 업데이트 로직 개선
    private void updateProgressValue(MissionProgress progress, Mission mission, TradeCompletionEvent event) {
        MissionConditionType type = mission.getConditionType();
        int goal = mission.getGoalValue();

        // A. 누적형 (카운트 증가) - 기존과 동일
        if (isCumulativeType(type)) {
            int valueToIncrease = calculateIncreaseValue(type, event);
            if (valueToIncrease > 0) {
                progress.incrementProgress(valueToIncrease);
                log.info("미션(누적) 갱신: MissionId={}, Added={}, Current={}",
                        mission.getId(), valueToIncrease, progress.getCurrentValue());
                checkMissionCompletion(progress);
            }
        }
        // B. 달성형 (임계값 돌파 / 최고 기록 갱신) - [수정됨]
        else if (isThresholdType(type)) {
            int eventValue = calculateThresholdValue(type, event);

            // 현재 기록보다 더 높은 기록이 나오면 갱신 (Best Record)
            if (eventValue > progress.getCurrentValue()) {
                // 목표치보다 크면 목표치로 고정 (100% 달성 표시를 위해)
                int newValue = Math.min(eventValue, goal);
                progress.setCurrentValue(newValue);

                log.info("미션(달성형) 기록 갱신: MissionId={}, NewBest={}, Goal={}",
                        mission.getId(), newValue, goal);

                // 목표 달성 여부 체크
                if (eventValue >= goal) {
                    checkMissionCompletion(progress);
                }
            }
        }
    }


    private boolean isCumulativeType(MissionConditionType type) {
        return switch (type) {
            case TRADE_COUNT, BUY_COUNT, SELL_COUNT,
                 BUY_AMOUNT, SELL_AMOUNT,
                 TOTAL_TRADE_AMOUNT, DAILY_PROFIT_COUNT, DAILY_TRADE_COUNT,

                 PROFIT_RATE // [추가] 수익률도 이제 차곡차곡 쌓는 '누적형'입니다.
                    -> true;

            default -> false;
        };
    }

    private boolean isThresholdType(MissionConditionType type) {
        // HOLDING_DAYS는 스케줄러가 처리하므로 제외
        return switch (type) {
            case PROFIT_AMOUNT -> true;
            default -> false;
        };
    }

    private int calculateIncreaseValue(MissionConditionType type, TradeCompletionEvent event) {
        return switch (type) {
            case TRADE_COUNT, BUY_COUNT, SELL_COUNT, DAILY_TRADE_COUNT -> 1;

            case BUY_AMOUNT, SELL_AMOUNT, TOTAL_TRADE_AMOUNT ->
                    event.getFilledAmount().intValue();

            case DAILY_PROFIT_COUNT -> {
                // 매도(SELL)이면서, 체결가가 평단가보다 크면 익절 (1회 증가)
                boolean isSell = event.getOrderMethod() == OrderMethod.SELL;
                boolean isProfit = event.getFilledPrice().compareTo(event.getBuyAveragePrice()) > 0;
                yield (isSell && isProfit) ? 1 : 0;
            }

            // [신규 이동] 수익률 누적 계산
            case PROFIT_RATE -> {
                if (event.getOrderMethod() != OrderMethod.SELL) yield 0;

                BigDecimal sellPrice = event.getFilledPrice();
                BigDecimal avgBuyPrice = event.getBuyAveragePrice();

                if (avgBuyPrice == null || avgBuyPrice.compareTo(BigDecimal.ZERO) == 0) {
                    yield 0;
                }

                // 수익률 공식: ((매도가 - 평단가) / 평단가) * 100
                BigDecimal profitRate = sellPrice.subtract(avgBuyPrice)
                        .divide(avgBuyPrice, 4, java.math.RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100));

                // 예: 5.5% 수익 -> 6점 증가 (반올림)
                // 예: -10% 손실 -> -10점 (진행도 깎임) -> 원치 않으시면 Math.max(0, ...) 처리 필요
                yield profitRate.setScale(0, java.math.RoundingMode.HALF_UP).intValue();
            }

            default -> 0;
        };
    }

    // [수정] 값 계산 시 반올림 적용 (선택 사항이나 권장)
    private int calculateThresholdValue(MissionConditionType type, TradeCompletionEvent event) {
        // 매도가 아니면 수익률/수익금 계산 불가
        if (event.getOrderMethod() != OrderMethod.SELL) return 0;

        // 1. 수익률 (PROFIT_RATE) 계산
        if (type == MissionConditionType.PROFIT_RATE) {
            // [수정] event.getProfitRate()를 신뢰하지 않고 직접 계산 로직을 우선 사용

            BigDecimal sellPrice = event.getFilledPrice();     // 매도 체결가
            BigDecimal avgBuyPrice = event.getBuyAveragePrice(); // 평단가

            // 평단가가 0이거나 없으면 계산 불가 (0 리턴)
            if (avgBuyPrice == null || avgBuyPrice.compareTo(BigDecimal.ZERO) == 0) {
                log.warn("수익률 계산 실패: 평단가가 0입니다. StockCode={}", event.getStockCode());
                return 0;
            }

            // 공식: ((매도가 - 평단가) / 평단가) * 100
            // 예: 매도가 10500, 평단가 10000 -> (500 / 10000) * 100 = 5%
            BigDecimal profitRate = sellPrice.subtract(avgBuyPrice)
                    .divide(avgBuyPrice, 4, java.math.RoundingMode.HALF_UP) // 소수점 4자리까지 확보 (0.0500)
                    .multiply(BigDecimal.valueOf(100)); // 백분율 변환 (5.00)

            // 로그로 계산 과정 출력 (디버깅용)
            log.info("수익률 계산: ({} - {}) / {} * 100 = {}%",
                    sellPrice, avgBuyPrice, avgBuyPrice, profitRate);

            // 소수점 반올림하여 정수로 반환 (예: 4.9% -> 5%, 4.4% -> 4%)
            return profitRate.setScale(0, java.math.RoundingMode.HALF_UP).intValue();
        }

        // 2. 수익금 (PROFIT_AMOUNT) 계산
        if (type == MissionConditionType.PROFIT_AMOUNT) {
            // 수익금은 직접 계산: (판 금액 - (평단가 * 수량))
            BigDecimal totalSellAmount = event.getFilledAmount();
            BigDecimal totalBuyCost = event.getBuyAveragePrice()
                    .multiply(BigDecimal.valueOf(event.getFilledQuantity()));

            BigDecimal profitAmount = totalSellAmount.subtract(totalBuyCost);

            return profitAmount.intValue();
        }

        return 0;
    }

    private boolean isMissionConditionMatches(Mission mission, TradeCompletionEvent event) {
        MissionConditionType type = mission.getConditionType();
        OrderMethod method = event.getOrderMethod();

        // 매수 전용
        if (type == MissionConditionType.BUY_COUNT || type == MissionConditionType.BUY_AMOUNT)
            return method == OrderMethod.BUY;

        // 매도 전용
        if (type == MissionConditionType.SELL_COUNT || type == MissionConditionType.SELL_AMOUNT ||
                type == MissionConditionType.PROFIT_RATE || type == MissionConditionType.DAILY_PROFIT_COUNT ||
                type == MissionConditionType.PROFIT_AMOUNT)
            return method == OrderMethod.SELL;

        // 공통
        if (type == MissionConditionType.TRADE_COUNT || type == MissionConditionType.TOTAL_TRADE_AMOUNT ||
                type == MissionConditionType.DAILY_TRADE_COUNT)
            return true;

        return false;
    }

    // ... 기존 메서드들 ...

    /**
     * [리팩토링] 연속 출석 초기화 로직
     * - 타입 안전성을 위해 Enum 상수를 직접 인자로 전달합니다.
     */
    @Transactional
    public void checkAndResetAttendanceStreaks() {
        log.info("연속 출석 끊김 여부 확인 및 초기화 시작 (Bulk Update)...");

        // 변경된 메서드 시그니처에 맞춰 Enum 값 전달
        int updatedCount = missionProgressRepository.bulkResetLoginStreakForAbsentees(
                MissionTrack.ACHIEVEMENT,           // :streakTrack (업적 트랙)
                MissionConditionType.LOGIN_STREAK,  // :streakCondition (연속 출석 체크용)
                MissionTrack.DAILY,                 // :dailyTrack (일일 미션 트랙)
                MissionConditionType.LOGIN_COUNT,   // :dailyCondition (일일 출석 여부 확인용)
                MissionStatus.COMPLETED             // :completedStatus (완료 상태 기준)
        );

        log.info("총 {}건의 연속 출석 기록이 일괄 초기화되었습니다.", updatedCount);
    }
    // 대시보드용 요약 정보 조회
    @Transactional(readOnly = true)
    public MissionDashboardDto getMissionDashboard(String email) {
        Member member = getMemberByEmail(email);

        // 1. 연속 출석 일수 (업적 중 LOGIN_STREAK 타입의 현재 진행도 조회)
// 7일, 15일, 30일 업적 미션들과 충돌하지 않음
        int streak = missionProgressRepository
                .findTopByMemberAndConditionOrderByGoalDesc(member, MissionTrack.ACHIEVEMENT, MissionConditionType.LOGIN_STREAK)
                .map(MissionProgress::getCurrentValue)
                .orElse(0); // 트래커 미션이 아직 생성 안 됐으면 0일

        // 2. 남은 일일 미션 개수 (DAILY 트랙 중, 완료되지 않은 것의 개수)
        List<MissionProgress> dailyMissions = missionProgressRepository.findAllByMemberAndMission_Track(member, MissionTrack.DAILY);
        int remaining = (int) dailyMissions.stream()
                .filter(mp -> !mp.isCompleted())
                .count();

        return MissionDashboardDto.builder()
                .consecutiveAttendanceDays(streak)
                .remainingDailyMissions(remaining)
                .build();
    }

    // 트랙별 미션 리스트 조회 (Enum 변환을 통한 안정성 확보)
    @Transactional(readOnly = true)
    public List<MissionListDto> getMissionsByTrack(String email, String trackName) {
        Member member = getMemberByEmail(email);
        List<MissionProgress> allProgress = missionProgressRepository.findByMemberWithMissionAndReward(member);

        // 1. "ALL"인 경우 전체 반환 (대소문자 무시: all, ALL 등)
        if ("ALL".equalsIgnoreCase(trackName)) {
            return allProgress.stream()
                    .map(MissionListDto::new)
                    .collect(Collectors.toList());
        }

        // 2. 특정 트랙 필터링 (Enum 변환 시도)
        try {
            // 입력값을 대문자로 변환하여 Enum 매핑 (daily -> DAILY)
            MissionTrack filterTrack = MissionTrack.valueOf(trackName.toUpperCase());

            return allProgress.stream()
                    .filter(mp -> mp.getMission().getTrack() == filterTrack) // Enum 타입 비교 (==)
                    .map(MissionListDto::new)
                    .collect(Collectors.toList());

        } catch (IllegalArgumentException e) {
            // 정의되지 않은 트랙 이름이 들어온 경우 (예: "ABCD")
            log.warn("유효하지 않은 미션 트랙 요청: email={}, track={}", email, trackName);
            return List.of(); // 빈 리스트 반환하여 에러 방지
        }
    }

    // 보유 칭호 목록 조회
    @Transactional(readOnly = true)
    public List<MemberTitleDto> getMyTitles(String email) {
        Member member = getMemberByEmail(email);
        return memberTitleRepository.findAllByMember(member).stream()
                .map(MemberTitleDto::new)
                .collect(Collectors.toList());
    }

    // --- [공통 로직] 완료 처리, 보상, 초기화 ---

    public void checkMissionCompletion(MissionProgress progress) {
        if (progress.getStatus() == MissionStatus.COMPLETED || !progress.isCompleted()) {
            return;
        }

        progress.complete();
        log.info("미션 완료: MemberId={}, MissionId={}", progress.getMember().getMemberId(), progress.getMission().getId());

        distributeReward(progress.getMember(), progress.getMission().getReward());
        activateNextMission(progress);
        handleMissionChain(progress);
        checkSeedCopierAchievement(progress.getMember());
    }

    /**
     * [신규] 시드 복사기 (누적 미션 30회) 체크
     */
    private void checkSeedCopierAchievement(Member member) {
        // 완료된 미션 총 개수 조회
        long completedCount = missionProgressRepository.countByMemberAndStatus(member, MissionStatus.COMPLETED);

        missionProgressRepository.findByMemberAndMissionTypeWithMission(member, MissionTrack.ACHIEVEMENT, MissionConditionType.TOTAL_MISSION_COUNT)
                .ifPresent(progress -> {
                    if (!progress.isCompleted()) {
                        // 현재 완료 횟수를 진행도에 반영
                        progress.setCurrentValue((int) completedCount);
                        if (completedCount >= progress.getMission().getGoalValue()) { // 30회
                            // 재귀 호출 방지를 위해 직접 complete 호출 (혹은 checkMissionCompletion 호출)
                            // 여기서는 간단히 내부 로직 실행
                            progress.complete();
                            distributeReward(member, progress.getMission().getReward());
                            log.info("'시드 복사기' 업적 달성! 총 완료 미션: {}개", completedCount);
                        }
                    }
                });
    }



    /**
     * [신규] 인생 2회차 (파산 신청) API 로직
     * - 조건: (보유 현금 + 보유 주식의 원금 총액) < 50,000원
     */
    @Transactional
    public Reward applyForBankruptcy(String email) {
        Member member = getMemberByEmail(email);
        // 1. 기본 계좌 조회
        Account account = accountRepository.findByMemberAndIsDefaultTrue(member)
                .orElseThrow(() -> new EntityNotFoundException("기본 계좌가 없습니다."));

// 2. 해당 계좌의 보유 주식 목록 조회 (Repository 사용)
        // Account 엔티티에 accountStocks 리스트가 없으므로 리포지토리로 별도 조회
        List<AccountStock> myStocks = accountStockRepository.findAllByAccount(account);

        // 3. 보유 주식 총 평가금액 계산 (평단가 * 보유수량)
        // [수정] getBuyPrice() -> getAveragePrice()
        BigDecimal totalStockAsset = myStocks.stream()
                .map(as -> as.getAveragePrice().multiply(BigDecimal.valueOf(as.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 3. 총 자산 (현금 + 주식 원금)
        BigDecimal totalAsset = account.getCash().add(totalStockAsset);

        // 4. 5만원 미만인지 확인
        if (totalAsset.compareTo(BigDecimal.valueOf(50000)) >= 0) {
            throw new IllegalStateException("아직 파산할 정도로 돈이 없지 않습니다. (자산: " + totalAsset + "원)");
        }

        // 5. 업적 달성 처리
        MissionProgress bankruptcyProgress = missionProgressRepository
                .findByMemberAndMissionTypeWithMission(member, MissionTrack.ACHIEVEMENT, MissionConditionType.ASSET_UNDER_THRESHOLD)
                .orElseThrow(() -> new EntityNotFoundException("인생 2회차 미션 데이터를 찾을 수 없습니다."));

        if (bankruptcyProgress.isCompleted()) {
            throw new IllegalStateException("이미 구조 지원금을 받으셨습니다.");
        }

        bankruptcyProgress.setCurrentValue(50000); // 조건 충족 표시
        bankruptcyProgress.complete();
        distributeReward(member, bankruptcyProgress.getMission().getReward());

        log.info("파산 신청 승인! 구조지원금 지급 완료. Member={}", member.getName());
        return bankruptcyProgress.getMission().getReward();
    }

    private void distributeReward(Member member, Reward reward) {
        if (reward == null) return;

        if (reward.getMoneyAmount() > 0) {
            accountRepository.findByMemberAndIsDefaultTrue(member)
                    .ifPresentOrElse(
                            acc -> {
                                acc.increaseCash(BigDecimal.valueOf(reward.getMoneyAmount()));
                                log.info("보상 지급: {}원", reward.getMoneyAmount());
                            },
                            () -> log.error("보상 지급 실패: 기본 계좌 없음 MemberId={}", member.getMemberId())
                    );
        }

        if (reward.getTitleToGrant() != null) {
            if (!memberTitleRepository.existsByMemberAndTitle(member, reward.getTitleToGrant())) {
                member.addMemberTitle(MemberTitle.builder()
                        .member(member)
                        .title(reward.getTitleToGrant())
                        .build());
                log.info("칭호 지급: {}", reward.getTitleToGrant().getName());
            }
        }
    }

    /**
     * [신규] 랭킹 Top 10 달성 처리 (스케줄러 호출용)
     * - RankingService에서 1분마다 Top 10 유저 ID 리스트를 넘겨줌
     */
    public void processRankerAchievement(List<Long> topRankerIds) {
        if (topRankerIds.isEmpty()) return;

        // 1. 'RANKING_TOP_10' 조건의 업적 미션 조회 (미션 ID: 909 '랭커')
        Mission rankerMission = missionRepository.findAllByTrackAndConditionType(MissionTrack.ACHIEVEMENT, MissionConditionType.RANKING_TOP_10)
                .stream().findFirst()
                .orElse(null);

        if (rankerMission == null) return;

        // 2. Top 10 유저들을 순회하며 미션 달성 처리
        for (Long memberId : topRankerIds) {
            Member member = memberRepository.findById(memberId).orElse(null);
            if (member == null) continue;

            // 3. 미션 진행도 조회 또는 생성
            MissionProgress progress = missionProgressRepository
                    .findByMemberAndMission(member, rankerMission)
                    .orElseGet(() -> {
                        MissionProgress newProgress = MissionProgress.builder()
                                .member(member)
                                .mission(rankerMission)
                                .status(MissionStatus.IN_PROGRESS)
                                .build();
                        member.addMissionProgress(newProgress);
                        return newProgress;
                    });

            // 4. 이미 완료한 사람은 패스 (칭호 중복 지급 방지)
            if (!progress.isCompleted()) {
                log.info("🏆 랭커 등극! 칭호 지급: MemberId={}", memberId);
                progress.setCurrentValue(10); // 목표치(10) 달성 처리
                checkMissionCompletion(progress); // 보상(칭호) 지급 및 완료 처리
            }
        }
    }

    private void activateNextMission(MissionProgress completedProgress) {
        Mission completedMission = completedProgress.getMission();
        Member member = completedProgress.getMember();

        if (completedMission.getTrack() == MissionTrack.DAILY || completedMission.getTrack() == MissionTrack.ACHIEVEMENT) {
            return;
        }

        Mission nextMission = completedMission.getNextMission();

        if (nextMission != null) {
            log.info("다음 미션 활성화: MissionId={}", nextMission.getId());
            missionProgressRepository.findByMemberAndMission(member, nextMission)
                    .orElseGet(() -> {
                        MissionProgress newProgress = MissionProgress.builder()
                                .member(member)
                                .mission(nextMission)
                                .status(MissionStatus.INACTIVE)
                                .build();
                        member.addMissionProgress(newProgress);
                        return newProgress;
                    }).activate();
        } else if (completedMission.getType() == MissionType.ADVANCED) {
            log.info("트랙 최종 완료: Track={}", completedMission.getTrack());
            resetMissionTrack(member, completedMission.getTrack());
        }
    }

    public void resetMissionTrack(Member member, MissionTrack track) {
        log.info("트랙 초기화 시작: MemberId={}, Track={}", member.getMemberId(), track);
        List<MissionProgress> progressList = missionProgressRepository.findAllByMemberAndMission_Track(member, track);

        for (MissionProgress progress : progressList) {
            progress.reset();
            progress.deactivate();

            // 트랙의 첫 번째 미션(중급 1단계)만 다시 활성화
            if (progress.getMission().getType() == MissionType.INTERMEDIATE && isFirstMissionInTrack(progress.getMission())) {
                progress.activate();
                log.info("트랙 첫 미션 재활성화: MissionId={}", progress.getMission().getId());
            }
        }
    }

    private boolean isFirstMissionInTrack(Mission mission) {
        long id = mission.getId();
        // data.sql 기준 첫 미션 ID (201: 단타, 301: 스윙, 401: 장기)
        return id == 201 || id == 301 || id == 401;
    }

    @Transactional
    public void resetDailyMissions() {
        log.info("일일 미션 전체 초기화 시작...");
        List<MissionProgress> dailyProgressList = missionProgressRepository.findAllByMission_Track(MissionTrack.DAILY);
        for (MissionProgress progress : dailyProgressList) {
            progress.reset();
        }

        // 2. [신규] Track = ACHIEVEMENT 이지만 'DAILY_TRADE_COUNT' 타입인 미션(카이팅 장인) 초기화
        // (완료하지 못한 경우에만 리셋해야 함)
        List<MissionProgress> kitingMissions = missionProgressRepository
                .findAllByMission_ConditionTypeAndStatus(MissionConditionType.DAILY_TRADE_COUNT, MissionStatus.IN_PROGRESS);

        for (MissionProgress mp : kitingMissions) {
            // 업적이라 트랙은 ACHIEVEMENT지만 성격은 Daily이므로 매일 리셋
            mp.setCurrentValue(0);
        }
        log.info("일일 미션 총 {}건 초기화 완료.", dailyProgressList.size());
    }

    @Transactional
    public void initializeMissionsForNewMember(Member newMember) {
        log.info("신규 회원 초기 미션 세팅 시작: MemberId={}", newMember.getMemberId());

        List<MissionTrack> tracks = Arrays.asList(MissionTrack.SHORT_TERM, MissionTrack.SWING, MissionTrack.LONG_TERM);
        List<Mission> dailyMissions = missionRepository.findAllByTrack(MissionTrack.DAILY);
        List<Mission> achievementMissions = missionRepository.findAllByTrack(MissionTrack.ACHIEVEMENT);
        List<Mission> intermediateMissions = missionRepository.findAllByTrackInAndType(tracks, MissionType.INTERMEDIATE);
        List<Mission> advancedMissions = missionRepository.findAllByTrackInAndType(tracks, MissionType.ADVANCED);

        List<Mission> allMissions = Stream.of(dailyMissions, achievementMissions, intermediateMissions, advancedMissions)
                .flatMap(List::stream)
                .collect(Collectors.toList());

        for (Mission mission : allMissions) {
            MissionStatus initialStatus = MissionStatus.INACTIVE;

            // 1. 일일 미션 & 업적 미션 -> 기본 진행 중
            if (mission.getTrack() == MissionTrack.DAILY || mission.getTrack() == MissionTrack.ACHIEVEMENT) {
                initialStatus = MissionStatus.IN_PROGRESS;
            }
            // 2. 트랙 미션 -> 첫 번째 미션만 진행 중
            else if (isFirstMissionInTrack(mission)) {
                initialStatus = MissionStatus.IN_PROGRESS;
            }

            MissionProgress newProgress = MissionProgress.builder()
                    .member(newMember)
                    .mission(mission)
                    .currentValue(0)
                    .status(initialStatus)
                    .build();
            newMember.addMissionProgress(newProgress);
        }
        log.info("신규 회원 초기 미션 총 {}건 세팅 완료.", allMissions.size());
    }

    // --- API 관련 메서드 (Controller 호출용) ---

    @Transactional
    public Reward claimDailyAttendance(String email) {
        Member member = getMemberByEmail(email);
        MissionProgress attendanceProgress = missionProgressRepository
                .findByMemberAndMissionTypeWithMission(member, MissionTrack.DAILY, MissionConditionType.LOGIN_COUNT)
                .orElseThrow(() -> new EntityNotFoundException("일일 출석 미션을 찾을 수 없습니다."));

        if (attendanceProgress.getStatus() == MissionStatus.COMPLETED || attendanceProgress.isCompleted()) {
            throw new IllegalStateException("오늘은 이미 출석 보상을 받았습니다.");
        }

        attendanceProgress.incrementProgress(1);
        checkMissionCompletion(attendanceProgress);
        return attendanceProgress.getMission().getReward();
    }

    @Transactional(readOnly = true)
    public List<MissionProgress> getMissionProgressList(String email) {
        Member member = getMemberByEmail(email);
        return missionProgressRepository.findByMemberWithMissionAndReward(member);
    }

    @Transactional
    public void handleReportView(String email) {
        handleDailySimpleMission(email, MissionConditionType.VIEW_REPORT);
    }

    @Transactional
    public void handlePortfolioAnalysis(String email) {
        handleDailySimpleMission(email, MissionConditionType.ANALYZE_PORTFOLIO);
    }

    private void handleDailySimpleMission(String email, MissionConditionType type) {
        Member member = getMemberByEmail(email);
        missionProgressRepository.findByMemberAndMissionTypeWithMission(member, MissionTrack.DAILY, type)
                .ifPresent(progress -> {
                    if (progress.getStatus() != MissionStatus.COMPLETED && !progress.isCompleted()) {
                        progress.incrementProgress(1);
                        log.info("일일 미션({}) 진행도 갱신: MemberId={}", type, member.getMemberId());
                        checkMissionCompletion(progress);
                    }
                });
    }

    // --- Helper Methods ---

    private Member getMemberByEmail(String email) {
        return memberRepository.findByEmail(email)
                .orElseThrow(() -> new EntityNotFoundException("회원을 찾을 수 없습니다. Email: " + email));
    }

    private void handleMissionChain(MissionProgress completedProgress) {
        Member member = completedProgress.getMember();
        Mission mission = completedProgress.getMission();

        // 일일 출석 완료 시 -> 연속 출석 업적 갱신
        if (mission.getTrack() == MissionTrack.DAILY &&
                mission.getConditionType() == MissionConditionType.LOGIN_COUNT) {
            log.info("연쇄 업적 갱신 시도: 일일 출석 -> 연속 출석");
            updateSpecificAchievement(member, MissionConditionType.LOGIN_STREAK, 1);
        }
    }

    private void updateSpecificAchievement(Member member, MissionConditionType conditionType, int valueToIncrease) {
        // 1. Optional -> List로 변경하여 해당 타입의 모든 업적 조회 (예: 3일, 7일, 30일 연속 등)
        List<Mission> achievements = missionRepository
                .findAllByTrackAndConditionType(MissionTrack.ACHIEVEMENT, conditionType);

        if (achievements.isEmpty()) return;

        // 2. 조회된 모든 업적에 대해 진행도 업데이트 반복
        for (Mission achievement : achievements) {
            MissionProgress achievementProgress = missionProgressRepository
                    .findByMemberAndMission(member, achievement)
                    .orElseGet(() -> {
                        MissionProgress newProgress = MissionProgress.builder()
                                .member(member)
                                .mission(achievement)
                                .status(MissionStatus.IN_PROGRESS)
                                .build();
                        member.addMissionProgress(newProgress);
                        return newProgress;
                    });

            // 이미 완료된 업적은 패스
            if (achievementProgress.getStatus() != MissionStatus.COMPLETED) {
                achievementProgress.incrementProgress(valueToIncrease);
                log.info("업적 미션({}) 갱신: MissionId={}, NewValue={}",
                        achievement.getName(), achievement.getId(), achievementProgress.getCurrentValue());

                checkMissionCompletion(achievementProgress);
            }
        }
    }

    // findDailyAttendanceMission 제거 (직접 쿼리 사용으로 대체됨)
}