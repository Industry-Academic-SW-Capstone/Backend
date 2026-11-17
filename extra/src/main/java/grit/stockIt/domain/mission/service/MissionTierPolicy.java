package grit.stockIt.domain.mission.service;

import java.util.List;

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

    // 시작 점수 오름차순. 기존 회원의 티어를 채우는 V19 의 CASE 문도 이 표와 같아야 한다.
    private static final List<Band> BANDS = List.of(
            new Band("BRONZE 1", 0),
            new Band("BRONZE 2", 800),
            new Band("BRONZE 3", 1000),
            new Band("SILVER 1", 1200),
            new Band("SILVER 2", 1400),
            new Band("SILVER 3", 1600),
            new Band("GOLD 1", 1800),
            new Band("GOLD 2", 2000),
            new Band("GOLD 3", 2200),
            new Band("MASTER 1", 2400),
            new Band("MASTER 2", 2600),
            new Band("MASTER 3", 2800),
            new Band("GRANDMASTER 1", 3000),
            new Band("GRANDMASTER 2", 3200),
            new Band("GRANDMASTER 3", 3400),
            new Band("LEGEND", LEGEND_THRESHOLD_SCORE)
    );

    private MissionTierPolicy() {
    }

    public record Band(String name, int startScore) {
    }

    public static int indexOf(int totalScore) {
        int index = 0;
        for (int i = 1; i < BANDS.size(); i++) {
            if (totalScore >= BANDS.get(i).startScore()) {
                index = i;
            }
        }
        return index;
    }

    public static Band band(int index) {
        return BANDS.get(index);
    }

    public static boolean isLast(int index) {
        return index == BANDS.size() - 1;
    }

    public static String tierOf(int totalScore) {
        return BANDS.get(indexOf(totalScore)).name();
    }
}
