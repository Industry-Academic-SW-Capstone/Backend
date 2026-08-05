package grit.stockIt.domain.mission.service;

/**
 * 미션 티어 정책 상수의 단일 출처(SSOT).
 *
 * LEGEND 등급 기준 점수는 보상 지급(MissionRewardService.checkLegendTier)과
 * 티어 조회(MissionQueryService.getTierInfo) 양쪽에서 참조되는데, 분해 후 두 파일로
 * 값이 갈라져 한쪽만 바꾸면 조용히 어긋날 수 있다. 이를 방지하기 위해 여기 한 곳에 둔다.
 */
public final class MissionTierPolicy {

    /** LEGEND 등급 도달 기준 총점 */
    public static final int LEGEND_THRESHOLD_SCORE = 3600;

    private MissionTierPolicy() {
    }
}
