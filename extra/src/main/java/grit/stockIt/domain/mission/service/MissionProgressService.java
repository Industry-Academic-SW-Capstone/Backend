package grit.stockIt.domain.mission.service;

import grit.stockIt.domain.mission.entity.Mission;
import grit.stockIt.domain.mission.entity.MissionProgress;
import grit.stockIt.domain.mission.enums.MissionConditionType;
import grit.stockIt.domain.mission.enums.MissionStatus;
import grit.stockIt.domain.mission.enums.MissionTrack;
import grit.stockIt.domain.mission.repository.MissionProgressRepository;
import grit.stockIt.domain.mission.repository.MissionRepository;
import grit.stockIt.domain.order.entity.OrderMethod;
import grit.stockIt.domain.mission.event.TradeCompletionEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * [B-4 분리] 거래 이벤트 기반 미션 진행도 갱신 서비스.
 * - 진입점: updateMissionProgress(TradeCompletionEvent), processRankerAchievement(랭킹 스케줄러 호출)
 * - 의존: 리포지토리 + B-1 순수 클래스 + MissionRewardService(단방향, 순환 금지)
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional // 미션 관련 로직은 하나의 트랜잭션으로 관리 (기존 MissionService와 동일 시맨틱스)
public class MissionProgressService {

    private final MissionRepository missionRepository;
    private final MissionProgressRepository missionProgressRepository;
    private final MissionConditionEvaluator missionConditionEvaluator;
    private final MissionProgressCalculator missionProgressCalculator;
    private final MissionRewardService missionRewardService;
    private final MissionTierTracker missionTierTracker;

    private static final long JUNK_STOCK_MARKET_CAP_THRESHOLD = 100000000000L;

    /**
     * [1] (이벤트 수신) 거래 이벤트 발생 시 미션 진행도 업데이트
     * - 일반 미션 갱신 로직
     * - 매도(SELL) 발생 시 '홀딩' 미션 초기화 로직 포함
     */
    public void updateMissionProgress(TradeCompletionEvent event) {
        log.info("수신된 거래 이벤트: MemberId={}, Method={}, Qty={}",
                event.getMemberId(), event.getOrderMethod(), event.getFilledQuantity());

        // 기본 계좌 거래만 센다. 기본 계좌인지는 증권사가 이벤트에 실어 보낸다.
        if (!event.isDefaultAccount()) {
            log.info("보조 계좌 거래 감지: 미션 및 랭킹 집계에서 제외합니다. (AccountId={})", event.getAccountId());
            return;
        }

        Long memberId = event.getMemberId();

        // 1. 회원의 '진행 중'인 미션 목록 조회
        List<MissionProgress> progressList = missionProgressRepository
                .findByMemberAndStatusWithMission(memberId, MissionStatus.IN_PROGRESS);

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
            if (missionConditionEvaluator.isMissionConditionMatches(mission, event)) {
                updateProgressValue(progress, mission, event);
            }
        }
        // 2. [신규] 특수 업적 미션 체크 (달콤한 첫입, 강형욱)
        checkSpecialAchievement(memberId, event);

        // [추가] 매도(SELL) 발생 시 실력 점수 반영
        if (event.getOrderMethod() == OrderMethod.SELL) {
            updateSkillScore(event);
        }
    }

    /**
     * [수정] 실력 점수 로직 -> "누적 수익금 업데이트"로 변경
     * - 매도 시 발생한 수익금(손실금)을 있는 그대로 더함
     * - 점수 변환(제곱근)은 조회 시점에 수행
     */
    private void updateSkillScore(TradeCompletionEvent event) {
        Long memberId = event.getMemberId();

        // 1. 이번 거래의 수익금 계산
        BigDecimal sellAmount = event.getFilledAmount();
        BigDecimal buyCost = event.getBuyAveragePrice().multiply(BigDecimal.valueOf(event.getFilledQuantity()));
        BigDecimal profit = sellAmount.subtract(buyCost);

        int profitInt = profit.intValue(); // 억 단위 이상이 아니라면 int로 충분
        if (profitInt == 0) return;

        // 2. 트래커(ID 999)에 수익금 누적
        missionProgressRepository.findByMemberAndMissionTypeWithMission(memberId, MissionTrack.ACHIEVEMENT, MissionConditionType.SKILL_SCORE)
                .ifPresent(tracker -> {
                    int currentTotalProfit = tracker.getCurrentValue();
                    int newTotalProfit = currentTotalProfit + profitInt;
                    String tierBefore = missionTierTracker.tierOf(memberId);

                    // 순수 수익금 저장 (마이너스 수익이면 전체 수익금이 깎임)
                    tracker.setCurrentValue(newTotalProfit);

                    log.info("누적 수익금 갱신: MemberId={}, 이번수익={}원, 총누적={}원",
                            memberId, profitInt, newTotalProfit);
                    missionTierTracker.publishIfChanged(memberId, tierBefore);

                    // Legend 달성 체크 (현재 총 수익금 기반으로 점수 환산하여 체크)
                    missionRewardService.checkLegendTier(memberId, getActivityScore(memberId) + missionProgressCalculator.calculateScoreFromProfit(newTotalProfit));
                });
    }

    /**
     * [Helper] 현재 활동 점수(Activity Score) 조회
     * - 트래커 미션(998번)의 현재 값을 가져옴
     * - 없으면 0점 반환
     */
    private int getActivityScore(Long memberId) {
        return missionProgressRepository.findByMemberAndMissionTypeWithMission(
                        memberId,
                        MissionTrack.ACHIEVEMENT,
                        MissionConditionType.ACTIVITY_SCORE
                )
                .map(MissionProgress::getCurrentValue)
                .orElse(0);
    }

    /**
     * [신규] 특수 조건 업적 처리
     * - 달콤한 첫입 (FIRST_PROFIT)
     * - 강형욱 (JUNK_STOCK_JACKPOT)
     */
    private void checkSpecialAchievement(Long memberId, TradeCompletionEvent event) {
        // 매도가 아니거나 수익이 없으면 패스
        if (event.getOrderMethod() != OrderMethod.SELL) return;

        // 수익 여부 판단 (매도가 > 평단가)
        boolean isProfit = event.getFilledPrice().compareTo(event.getBuyAveragePrice()) > 0;
        if (!isProfit) return;

        // A. 달콤한 첫입 (첫 수익 실현)
        handleOneTimeAchievement(memberId, MissionConditionType.FIRST_PROFIT, 1);

        // B. 강형욱 (잡주로 100% 이상 수익)

/*        // 시가총액 1,000억 미만이고, 수익률이 100% 이상인 경우
        if (stock != null && stock.getMarketCap() < JUNK_STOCK_MARKET_CAP_THRESHOLD) {
            // 수익률 계산: (매도가 - 평단가) / 평단가
            BigDecimal profitRate = event.getFilledPrice().subtract(event.getBuyAveragePrice())
                    .divide(event.getBuyAveragePrice(), 2, java.math.RoundingMode.HALF_UP);

            // 1.0 이상 (100%)
            if (profitRate.compareTo(BigDecimal.ONE) >= 0) {
                log.info("잡주 대박 터짐! MemberId={}, Stock={}, Rate={}", memberId, stock.getName(), profitRate);
                handleOneTimeAchievement(memberId, MissionConditionType.JUNK_STOCK_JACKPOT, 1);
            }
        }*/
    }

    /**
     * 1회성 업적 달성 처리 헬퍼 (이미 완료되었으면 무시)
     */
    private void handleOneTimeAchievement(Long memberId, MissionConditionType type, int value) {
        missionProgressRepository.findByMemberAndMissionTypeWithMission(memberId, MissionTrack.ACHIEVEMENT, type)
                .ifPresent(progress -> {
                    if (!progress.isCompleted()) {
                        progress.setCurrentValue(value);
                        missionRewardService.checkMissionCompletion(progress);
                    }
                });
    }

    // [수정] 진행도 업데이트 로직 개선
    private void updateProgressValue(MissionProgress progress, Mission mission, TradeCompletionEvent event) {
        MissionConditionType type = mission.getConditionType();
        int goal = mission.getGoalValue();

        // A. 누적형 (카운트 증가) - 기존과 동일
        if (missionConditionEvaluator.isCumulativeType(type)) {
            int valueToIncrease = missionProgressCalculator.calculateIncreaseValue(type, event);
            if (valueToIncrease > 0) {
                progress.incrementProgress(valueToIncrease);
                log.info("미션(누적) 갱신: MissionId={}, Added={}, Current={}",
                        mission.getId(), valueToIncrease, progress.getCurrentValue());
                missionRewardService.checkMissionCompletion(progress);
            }
        }
        // B. 달성형 (임계값 돌파 / 최고 기록 갱신) - [수정됨]
        else if (missionConditionEvaluator.isThresholdType(type)) {
            int eventValue = missionProgressCalculator.calculateThresholdValue(type, event);

            // 현재 기록보다 더 높은 기록이 나오면 갱신 (Best Record)
            if (eventValue > progress.getCurrentValue()) {
                // 목표치보다 크면 목표치로 고정 (100% 달성 표시를 위해)
                int newValue = Math.min(eventValue, goal);
                progress.setCurrentValue(newValue);

                log.info("미션(달성형) 기록 갱신: MissionId={}, NewBest={}, Goal={}",
                        mission.getId(), newValue, goal);

                // 목표 달성 여부 체크
                if (eventValue >= goal) {
                    missionRewardService.checkMissionCompletion(progress);
                }
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
            // 회원 테이블은 증권사 것이다. 미션 데이터가 없는 회원(없는 번호)은 건너뛴다.
            if (!missionProgressRepository.existsByMemberId(memberId)) continue;

            // 3. 미션 진행도 조회 또는 생성
            MissionProgress progress = missionProgressRepository
                    .findByMemberIdAndMission(memberId, rankerMission)
                    .orElseGet(() -> {
                        MissionProgress newProgress = MissionProgress.builder()
                                .memberId(memberId)
                                .mission(rankerMission)
                                .status(MissionStatus.IN_PROGRESS)
                                .build();
                        return missionProgressRepository.save(newProgress);
                    });

            // 4. 이미 완료한 사람은 패스 (칭호 중복 지급 방지)
            if (!progress.isCompleted()) {
                log.info("랭커 등극! 칭호 지급: MemberId={}", memberId);
                progress.setCurrentValue(10); // 목표치(10) 달성 처리
                missionRewardService.checkMissionCompletion(progress); // 보상(칭호) 지급 및 완료 처리
            }
        }
    }
}
