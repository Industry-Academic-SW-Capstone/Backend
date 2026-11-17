package grit.stockIt.domain.mission.enums;

/**
 * 미션의 성격 및 단계 구분
 * (MissionTrack과 조합되어 사용됩니다)
 */
public enum MissionType {
    // MissionTrack의 DAILY, ACHIEVEMENT는 이 타입을 크게 신경쓰지 않아도 됨 (혹은 COMMON 등을 사용)
    COMMON,         // 일반 (일일, 업적 등)
    INTERMEDIATE,   // 중급 (단타, 스윙, 장기 트랙의 시작)
    ADVANCED        // 고급 (단타, 스윙, 장기 트랙의 연계)
}