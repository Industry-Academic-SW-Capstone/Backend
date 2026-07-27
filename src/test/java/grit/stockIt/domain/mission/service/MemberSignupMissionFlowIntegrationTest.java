package grit.stockIt.domain.mission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import grit.stockIt.domain.account.entity.Account;
import grit.stockIt.domain.account.repository.AccountRepository;
import grit.stockIt.domain.auth.service.KakaoAuthService;
import grit.stockIt.domain.member.dto.MemberSignupRequest;
import grit.stockIt.domain.member.entity.Member;
import grit.stockIt.domain.member.repository.MemberRepository;
import grit.stockIt.domain.member.service.LocalMemberService;
import grit.stockIt.domain.mission.entity.Mission;
import grit.stockIt.domain.mission.entity.MissionProgress;
import grit.stockIt.domain.mission.enums.MissionStatus;
import grit.stockIt.domain.mission.repository.MissionProgressRepository;
import grit.stockIt.domain.mission.repository.MissionRepository;
import grit.stockIt.global.jwt.JwtToken;
import grit.stockIt.global.support.IntegrationTestSupport;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.jdbc.Sql;

/**
 * [특성화 테스트 / A-5] 회원가입 → 계좌 생성 → 미션 초기화 도메인 간 커맨드 경로.
 *
 * 리팩토링(커맨드 → 이벤트 전환) 전후로 무수정 재사용하기 위해
 * 관찰 가능한 결과(DB 상태)만 단언하고, 호출/주입 메커니즘은 검증하지 않는다.
 *
 * 시드: data.sql (idempotent upsert) — 트랙 첫 미션 201/301/401, 활동 점수 트래커 998.
 * 기본 대회(Contest)는 DefaultContestInitializer(ApplicationRunner)가 컨텍스트 기동 시 생성한다.
 */
@Sql(scripts = "/data.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class MemberSignupMissionFlowIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private LocalMemberService localMemberService;

    @Autowired
    private KakaoAuthService kakaoAuthService;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private MissionRepository missionRepository;

    @Autowired
    private MissionProgressRepository missionProgressRepository;

    /**
     * 롤백 전파 시나리오에서만 강제 예외를 스터빙한다.
     * 스터빙하지 않은 테스트에서는 실제 빈으로 위임되므로 성공 경로 동작에 영향이 없다.
     */
    @MockitoSpyBean
    private MissionService missionService;

    private static String uniqueEmail(String prefix) {
        // 컨테이너 재사용(withReuse) + 실커밋 테스트이므로 이메일 충돌 방지를 위해 매번 유일한 값 사용
        // 주의: 로컬 가입은 이메일 로컬파트를 name으로 쓰는데 member.name 컬럼이 varchar(20)이라 짧게 유지
        return prefix + UUID.randomUUID().toString().substring(0, 13).replace("-", "") + "@test.com";
    }

    @Test
    @DisplayName("로컬 가입 성공 시 member/account/mission_progress가 한 번에 생성된다")
    void 로컬_가입_성공시_member_account_missionProgress가_함께_생성된다() {
        // given
        String email = uniqueEmail("loc");
        MemberSignupRequest request = new MemberSignupRequest(email, "password123!");

        // when
        localMemberService.signup(request);

        // then: member 생성
        Optional<Member> found = memberRepository.findByEmail(email);
        assertThat(found).isPresent();
        Member member = found.get();

        // then: 디폴트 계좌 생성 (기본 대회 기준, isDefault=true)
        Optional<Account> defaultAccount = accountRepository.findByMemberAndIsDefaultTrue(member);
        assertThat(defaultAccount).isPresent();

        // then: 트랙 첫 미션(201/301/401)만 IN_PROGRESS, 진행도 0
        assertTrackFirstMissionActive(member, 201L);
        assertTrackFirstMissionActive(member, 301L);
        assertTrackFirstMissionActive(member, 401L);

        // then: 활동 점수 트래커(998)는 초기값 1200 (Silver 1티어 시작)
        MissionProgress activityScore = progressOf(member, 998L);
        // 특성화: 현재 동작 — ACTIVITY_SCORE 트래커만 0이 아닌 1200으로 하드코딩 시작
        assertThat(activityScore.getCurrentValue()).isEqualTo(1200);
        assertThat(activityScore.getStatus()).isEqualTo(MissionStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("카카오 completeSignup 경로도 member/account/mission_progress를 함께 생성한다")
    void 카카오_completeSignup_경로도_member_account_missionProgress를_함께_생성한다() {
        // completeSignup은 카카오 OAuth 외부 호출 없이 DB 저장 + JWT 발급만 수행하므로 직접 호출 가능(실측 확인)
        // given
        String email = uniqueEmail("kko");

        // when
        JwtToken token = kakaoAuthService.completeSignup(email, "카카오테스터", null);

        // then: JWT 발급
        assertThat(token.getAccessToken()).isNotBlank();

        // then: member/account/mission_progress 동시 존재
        Optional<Member> found = memberRepository.findByEmail(email);
        assertThat(found).isPresent();
        Member member = found.get();

        assertThat(accountRepository.findByMemberAndIsDefaultTrue(member)).isPresent();

        assertTrackFirstMissionActive(member, 201L);
        assertTrackFirstMissionActive(member, 301L);
        assertTrackFirstMissionActive(member, 401L);

        MissionProgress activityScore = progressOf(member, 998L);
        assertThat(activityScore.getCurrentValue()).isEqualTo(1200);
        assertThat(activityScore.getStatus()).isEqualTo(MissionStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("미션 초기화 실패 시 signup 트랜잭션 전체가 롤백된다 (member/account 행 부재)")
    void 미션_초기화_실패시_signup_전체가_롤백된다() {
        // given
        String email = uniqueEmail("rbk");
        MemberSignupRequest request = new MemberSignupRequest(email, "password123!");
        long accountCountBefore = accountRepository.count();

        doThrow(new RuntimeException("미션 초기화 강제 실패"))
                .when(missionService).initializeMissionsForNewMember(any(Member.class));

        // when & then: 예외가 signup 호출자까지 전파된다
        // 특성화: 현재 동작 — 미션 초기화 실패가 가입 전체를 실패시킴 (커맨드 방식 동기 결합)
        assertThatThrownBy(() -> localMemberService.signup(request))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("미션 초기화 강제 실패");

        // then: member/account 행 부재 (전체 롤백)
        assertThat(memberRepository.findByEmail(email)).isEmpty();
        assertThat(accountRepository.count()).isEqualTo(accountCountBefore);
    }

    private void assertTrackFirstMissionActive(Member member, long missionId) {
        MissionProgress progress = progressOf(member, missionId);
        assertThat(progress.getStatus()).isEqualTo(MissionStatus.IN_PROGRESS);
        assertThat(progress.getCurrentValue()).isZero();
    }

    private MissionProgress progressOf(Member member, long missionId) {
        Mission mission = missionRepository.findById(missionId)
                .orElseThrow(() -> new IllegalStateException("data.sql 시드 미션 없음: " + missionId));
        return missionProgressRepository.findByMemberAndMission(member, mission)
                .orElseThrow(() -> new IllegalStateException("mission_progress 없음: missionId=" + missionId));
    }
}
