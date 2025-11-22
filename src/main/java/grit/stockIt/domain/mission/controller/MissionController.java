package grit.stockIt.domain.mission.controller;

import grit.stockIt.domain.mission.dto.*;
import grit.stockIt.domain.mission.entity.MissionProgress;
import grit.stockIt.domain.mission.entity.Reward;
import grit.stockIt.domain.mission.service.MissionService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import grit.stockIt.domain.mission.scheduler.MissionScheduler;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/missions")
public class MissionController {

    private final MissionService missionService;
    private final MissionScheduler missionScheduler;

    /**
     * [신규] 대시보드 위젯 정보 조회
     * - 연속 출석 일수
     * - 남은 일일 미션 개수
     */
    @GetMapping("/dashboard")
    @Operation(summary = "미션 대시보드 요약", description = "메인화면 상단 위젯에 표시할 연속 출석일과 남은 미션 수를 반환합니다.")
    public ResponseEntity<MissionDashboardDto> getDashboardSummary(@AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(missionService.getMissionDashboard(userDetails.getUsername()));
    }

    /**
     * [수정] 미션 목록 조회 (트랙별 필터링 지원)
     * - param track: DAILY, SHORT_TERM, SWING, LONG_TERM, ACHIEVEMENT
     * - track 파라미터가 없거나 "ALL"이면 전체 반환
     */
    @GetMapping
    @Operation(summary = "미션 목록 조회", description = "트랙별 미션 및 업적 진행도를 반환합니다. 보상 정보(금액/칭호)가 포함됩니다.")
    public ResponseEntity<List<MissionListDto>> getMissions(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam(required = false, defaultValue = "ALL") String track
    ) {
        return ResponseEntity.ok(missionService.getMissionsByTrack(userDetails.getUsername(), track));
    }

    /**
     * [신규] 보유 칭호 목록 조회
     */
    @GetMapping("/titles")
    @Operation(summary = "보유 칭호 조회", description = "사용자가 획득한 모든 칭호 목록을 반환합니다.")
    public ResponseEntity<List<MemberTitleDto>> getMyTitles(@AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(missionService.getMyTitles(userDetails.getUsername()));
    }

    /**
     * POST /api/missions/attendance
     * 일일 출석 보상을 수령합니다.
     */
    @PostMapping("/attendance")
    public ResponseEntity<RewardResponseDto> claimDailyAttendance(
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        String email = userDetails.getUsername();
        log.info("Request: claimDailyAttendance for Email={}", email);

        // 1. 서비스 호출
        Reward savedReward = missionService.claimDailyAttendance(email);

        // 2. Entity -> DTO 변환
        RewardResponseDto responseDto = new RewardResponseDto(savedReward);

        return ResponseEntity.ok(responseDto);
    }

    /**
     * POST /api/missions/view-report
     * '종목 리포트 보기' 미션의 진행도를 1 증가시킵니다.
     */
    @PostMapping("/view-report")
    @ResponseStatus(HttpStatus.OK)
    public void trackReportView(
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        String email = userDetails.getUsername();
        log.info("Request: trackReportView for Email={}", email);

        // 서비스 호출
        missionService.handleReportView(email);
    }

    /**
     * POST /api/missions/analyze-portfolio
     * '포트폴리오 분석' 미션의 진행도를 1 증가시킵니다.
     */
    @PostMapping("/analyze-portfolio")
    @ResponseStatus(HttpStatus.OK)
    public void trackPortfolioAnalysis(
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        String email = userDetails.getUsername();
        log.info("Request: trackPortfolioAnalysis for Email={}", email);

        // 서비스 호출
        missionService.handlePortfolioAnalysis(email);
    }

    /**
     * POST /api/missions/bankruptcy
     * [신규] 인생 2회차(파산 신청)
     */
    @PostMapping("/bankruptcy")
    @Operation(summary = "파산 신청 (인생 2회차)", description = "총 자산이 5만원 미만일 때 구조지원금을 신청합니다.")
    public ResponseEntity<RewardResponseDto> applyForBankruptcy(
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        // 토큰에서 이메일(String)만 추출해서 넘김
        String email = userDetails.getUsername();

        // 서비스가 이메일로 멤버 찾아서 로직 수행
        Reward reward = missionService.applyForBankruptcy(email);

        return ResponseEntity.ok(new RewardResponseDto(reward));
    }
}