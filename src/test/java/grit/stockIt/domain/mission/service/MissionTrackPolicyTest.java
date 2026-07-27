package grit.stockIt.domain.mission.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * [B-1 단위 특성화] MissionTrackPolicy — data.sql 시드 기준 트랙 첫 미션 ID(201/301/401) 판정 고정.
 */
@DisplayName("MissionTrackPolicy 단위 특성화 테스트")
class MissionTrackPolicyTest {

    private final MissionTrackPolicy policy = new MissionTrackPolicy();

    @ParameterizedTest
    @ValueSource(longs = {201L, 301L, 401L})
    void 트랙_첫_미션_ID는_201_301_401이다(long missionId) {
        assertThat(policy.isFirstMissionInTrack(missionId)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, 101L, 200L, 202L, 300L, 302L, 400L, 402L, 904L, 998L})
    void 그_외_ID는_트랙_첫_미션이_아니다(long missionId) {
        assertThat(policy.isFirstMissionInTrack(missionId)).isFalse();
    }
}
