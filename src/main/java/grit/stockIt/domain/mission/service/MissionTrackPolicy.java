package grit.stockIt.domain.mission.service;

import org.springframework.stereotype.Component;

/**
 * [B-1 추출] 트랙 구조 정책 순수 로직 (data.sql 시드 ID 캡슐화).
 * Spring 컨텍스트 없이 new로 직접 생성하여 단위 테스트할 수 있다.
 */
@Component
public class MissionTrackPolicy {

    public boolean isFirstMissionInTrack(long missionId) {
        // data.sql 기준 첫 미션 ID (201: 단타, 301: 스윙, 401: 장기)
        return missionId == 201 || missionId == 301 || missionId == 401;
    }
}
