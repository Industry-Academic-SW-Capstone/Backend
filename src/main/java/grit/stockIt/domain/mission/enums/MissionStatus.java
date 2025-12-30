package grit.stockIt.domain.mission.enums;

/**
 * 회원의 미션 진행 상태
 */
public enum MissionStatus {
    INACTIVE,       // 비활성 (예: 선행 미션 미완료)
    IN_PROGRESS,    // 진행 중 (활성화 상태)
    COMPLETED       // 완료
}