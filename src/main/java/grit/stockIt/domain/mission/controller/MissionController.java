package grit.stockIt.domain.mission.controller;

import grit.stockIt.domain.mission.dto.MissionProgressResponseDto;
import grit.stockIt.domain.mission.dto.RewardResponseDto;
import grit.stockIt.domain.mission.entity.MissionProgress;
import grit.stockIt.domain.mission.entity.Reward;
import grit.stockIt.domain.mission.service.MissionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/missions")
public class MissionController {

    private final MissionService missionService;

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
}