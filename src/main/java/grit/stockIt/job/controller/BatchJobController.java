package grit.stockIt.job.controller;

import grit.stockIt.job.service.MasterFileUpdateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 배치 작업 컨트롤러
 */
@Slf4j
@RestController
@RequestMapping("/api/batch-jobs")
@Tag(name = "jobs", description = "배치 작업 관련 API")
@RequiredArgsConstructor
public class BatchJobController {

    private final MasterFileUpdateService masterFileUpdateService;

    /**
     * 마스터 파일 업데이트 작업 수동 실행
     * @return 작업 실행 결과 메시지
     */
    @Operation(summary = "마스터 파일 업데이트", description = "KOSPI/KOSDAQ 종목 마스터 파일을 수동으로 업데이트합니다")
    @PostMapping("/update-master-files")
    public ResponseEntity<String> triggerMasterFileUpdate() {
        log.info("마스터 파일 업데이트 작업 수동 실행 요청 수신.");
        try {
            // 서비스의 메인 업데이트 메서드 호출
            masterFileUpdateService.updateMasterFiles();
            String successMessage = "마스터 파일 업데이트 작업이 수동 요청으로 성공적으로 실행되었습니다.";
            log.info(successMessage);

            return ResponseEntity.ok(successMessage); // 성공 시 200 OK 반환

        } catch (Exception e) {
            String errorMessage = "마스터 파일 업데이트 작업 수동 실행 중 오류 발생.";
            log.error(errorMessage, e);

            // 실패 시 500 Internal Server Error 반환
            return ResponseEntity.internalServerError().body(errorMessage + " 원인: " + e.getMessage());

        }
    }
}