package grit.stockIt.domain.mission.controller;

import grit.stockIt.domain.mission.service.MissionSeedService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 관리자 API(/api/admin/** 는 ROLE_ADMIN 만).
@RestController
@RequestMapping("/api/admin/missions")
@Tag(name = "mission-admin", description = "미션 관리자 API")
@RequiredArgsConstructor
public class MissionSeedAdminController {

    private final MissionSeedService missionSeedService;

    @Operation(summary = "미션 시드 반영", description = "칭호·보상·미션 정의(data.sql)를 DB 에 upsert 합니다. 여러 번 실행해도 같습니다.")
    @PostMapping("/seed")
    public ResponseEntity<String> reloadSeed() {
        missionSeedService.reload();
        return ResponseEntity.ok("미션 시드를 반영했습니다.");
    }
}
