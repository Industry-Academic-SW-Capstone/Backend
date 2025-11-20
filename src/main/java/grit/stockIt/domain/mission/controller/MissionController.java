package grit.stockIt.domain.mission.controller;

import grit.stockIt.domain.mission.dto.MissionProgressResponseDto;
import grit.stockIt.domain.mission.dto.RewardResponseDto;
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
     * GET /api/missions
     * 현재 사용자의 모든 미션 목록을 조회합니다.
     */
    @GetMapping
    public ResponseEntity<List<MissionProgressResponseDto>> getMyMissions(
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        String email = userDetails.getUsername();
        log.info("Request: getMyMissions for Email={}", email);

        // 1. 서비스 호출 (이메일만 전달)
        List<MissionProgress> progressList = missionService.getMissionProgressList(email);

        // 2. Entity List -> DTO List 변환
        List<MissionProgressResponseDto> responseDtoList = progressList.stream()
                .map(MissionProgressResponseDto::new)
                .collect(Collectors.toList());

        return ResponseEntity.ok(responseDtoList);
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

    /**
     * GET /api/missions/scheduler/holding
     * [테스트용] 홀딩 미션 업데이트 스케줄러를 강제로 실행합니다.
     * 브라우저 주소창에서 바로 호출 가능합니다.
     */
    @GetMapping("/scheduler/holding")
    @Operation(summary = "[테스트] 홀딩 미션 강제 업데이트", description = "전체 유저의 홀딩 미션(HOLDING_DAYS) 진행도를 +1 증가시킵니다.")
    public ResponseEntity<String> forceRunHoldingScheduler() {
        log.info("☢️ [TEST] 홀딩 미션 스케줄러 강제 실행 요청됨");

        // 스케줄러의 메서드를 직접 호출 (로그 및 예외처리 포함됨)
        missionScheduler.dailyHoldingProgressTask();

        return ResponseEntity.ok("✅ 홀딩 미션 진행도 업데이트가 완료되었습니다.");
    }
}