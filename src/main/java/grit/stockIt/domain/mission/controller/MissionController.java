package grit.stockIt.domain.mission.controller;

import grit.stockIt.domain.mission.dto.ClaimRewardRequest;
import grit.stockIt.domain.mission.dto.MissionResponse;
import grit.stockIt.domain.mission.service.MissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/missions")
@RequiredArgsConstructor
public class MissionController {
    private final MissionService missionService;

    // 유저의 모든 미션 조회
    @GetMapping("/user/{userId}")
    public ResponseEntity<MissionResponse> getUserMissions(@PathVariable Long userId) {
        MissionResponse response = missionService.getMissions(userId);
        return ResponseEntity.ok(response);
    }

    // 미션 진행도 업데이트
    @PutMapping("/{missionId}/progress")
    public ResponseEntity<Void> updateMissionProgress(
            @RequestHeader("X-USER-ID") Long userId,
            @PathVariable Long missionId) {
        missionService.updateMissionProgress(userId, missionId);
        return ResponseEntity.ok().build();
    }

    // 미션 보상 수령
    @PostMapping("/{missionId}/claim")
    public ResponseEntity<Void> claimReward(
            @RequestHeader("X-USER-ID") Long userId,
            @PathVariable Long missionId,
            @RequestBody ClaimRewardRequest request) {
        missionService.claimReward(userId, request);
        return ResponseEntity.ok().build();
    }

    // 미션 재시작
    @PostMapping("/{missionId}/restart")
    public ResponseEntity<Void> restartMission(
            @RequestHeader("X-USER-ID") Long userId,
            @PathVariable Long missionId) {
        missionService.restartMission(userId, missionId);
        return ResponseEntity.ok().build();
    }

    // IllegalArgumentException 핸들러 추가
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ex.getMessage());
    }
}