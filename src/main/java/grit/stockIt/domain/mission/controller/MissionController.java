package grit.stockIt.domain.mission.controller;

import grit.stockIt.domain.member.entity.Member; // 1. [수정] Member 임포트
import grit.stockIt.domain.member.repository.MemberRepository; // 2. [수정] MemberRepository 임포트
import grit.stockIt.domain.mission.dto.MissionProgressResponseDto;
import grit.stockIt.domain.mission.dto.RewardResponseDto;
import grit.stockIt.domain.mission.entity.MissionProgress;
import grit.stockIt.domain.mission.entity.Reward;
import grit.stockIt.domain.mission.service.MissionService;
import jakarta.persistence.EntityNotFoundException; // 3. [수정] 예외 임포트
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.annotation.ResponseStatus; // [추가]

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/missions")
public class MissionController {

    private final MissionService missionService;
    private final MemberRepository memberRepository; // 4. [수정] Repository 의존성 주입

    /**
     * GET /api/missions
     * 현재 사용자의 모든 미션 목록을 조회합니다.
     */
    @GetMapping
    public ResponseEntity<List<MissionProgressResponseDto>> getMyMissions(
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        // 5. [수정] UserDetails에서 email을 F=get, memberId 조회
        String email = userDetails.getUsername();
        Member member = memberRepository.findByEmail(email)
                .orElseThrow(() -> new EntityNotFoundException("로그인한 회원을 찾을 수 없습니다."));
        Long memberId = member.getMemberId();

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
        // 6. [수정] UserDetails에서 email을 F=get, memberId 조회
        String email = userDetails.getUsername();
        Member member = memberRepository.findByEmail(email)
                .orElseThrow(() -> new EntityNotFoundException("로그인한 회원을 찾을 수 없습니다."));
        Long memberId = member.getMemberId();

        log.info("Request: claimDailyAttendance for MemberId={}", memberId);

        // 1. 서비스 호출 (보상 수령 및 7일 업적 갱신)
        Reward savedReward = missionService.claimDailyAttendance(memberId);

        // 2. Entity -> DTO 변환
        RewardResponseDto responseDto = new RewardResponseDto(savedReward);

        return ResponseEntity.ok(responseDto);
    }

    /**
     * POST /api/missions/view-report
     * '종목 리포트 보기' 미션의 진행도를 1 증가시킵니다.
     */
    @PostMapping("/view-report")
    @ResponseStatus(HttpStatus.OK) // 성공 시 200 OK만 반환 (별도 DTO 없음)
    public void trackReportView(
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        // 1. [공통] UserDetails에서 memberId 조회
        String email = userDetails.getUsername();
        Member member = memberRepository.findByEmail(email)
                .orElseThrow(() -> new EntityNotFoundException("로그인한 회원을 찾을 수 없습니다."));
        Long memberId = member.getMemberId();

        log.info("Request: trackReportView for MemberId={}", memberId);

        // 2. 서비스 호출
        missionService.handleReportView(memberId);
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
        // 1. [공통] UserDetails에서 memberId 조회
        String email = userDetails.getUsername();
        Member member = memberRepository.findByEmail(email)
                .orElseThrow(() -> new EntityNotFoundException("로그인한 회원을 찾을 수 없습니다."));
        Long memberId = member.getMemberId();

        log.info("Request: trackPortfolioAnalysis for MemberId={}", memberId);

        // 2. 서비스 호출
        missionService.handlePortfolioAnalysis(memberId);
    }
}