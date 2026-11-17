package grit.stockIt.domain.mission.enums;

/**
 * 미션의 최상위 분류 (트랙)
 * 요구사항의 5가지 분류(일일, 단타, 스윙, 장기, 업적)에 해당합니다.
 */
public enum MissionTrack {
    DAILY,          // 일일 미션
    SHORT_TERM,     // 단타 미션 (중급/고급 존재)
    SWING,          // 스윙 미션 (중급/고급 존재)
    LONG_TERM,      // 장기 미션 (중급/고급 존재)
    ACHIEVEMENT     // 업적 미션
}