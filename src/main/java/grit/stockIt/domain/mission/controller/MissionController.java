package grit.stockIt.domain.mission.controller;

import grit.stockIt.domain.mission.dto.MissionProgressResponseDto;
import grit.stockIt.domain.mission.dto.RewardResponseDto;
import grit.stockIt.domain.mission.entity.MissionProgress;
import grit.stockIt.domain.mission.entity.Reward;
import grit.stockIt.domain.mission.service.MissionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails; // Spring Security
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
            @AuthenticationPrincipal UserDetails userDetails // (또는 커스텀 UserDetails)
    ) {
        // [Security] UserDetails에서 memberId를 추출하는 로직 필요
        // 예: Long memberId = ((CustomUserDetails) userDetails).getMemberId();
        Long memberId = 1L; // (임시로 1L 사용, 실제로는 위처럼 추출)
        log.info("Request: getMyMissions for MemberId={}", memberId);

        // 1. 서비스 호출 (N+1이 해결된 조회)
        List<MissionProgress> progressList = missionService.getMissionProgressList(memberId);

        // 2. Entity List -> DTO List 변환
        List<MissionProgressResponseDto> responseDtoList = progressList.stream()
                .map(MissionProgressResponseDto::new) // 생성자 참조
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
        // [Security] UserDetails에서 memberId를 추출하는 로직 필요
        Long memberId = 1L; // (임시로 1L 사용)
        log.info("Request: claimDailyAttendance for MemberId={}", memberId);

        // 1. 서비스 호출 (보상 수령 및 7일 업적 갱신)
        Reward savedReward = missionService.claimDailyAttendance(memberId);

        // 2. Entity -> DTO 변환
        RewardResponseDto responseDto = new RewardResponseDto(savedReward);

        return ResponseEntity.ok(responseDto);
    }
}