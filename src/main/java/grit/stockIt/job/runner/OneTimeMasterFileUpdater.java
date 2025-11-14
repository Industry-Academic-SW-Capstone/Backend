package grit.stockIt.job.runner;

import grit.stockIt.job.service.MasterFileUpdateService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 개발/시연용: 애플리케이션 시작 시 조건부로 마스터파일 업데이트를 한 번 실행합니다.
 * 활성화 방법: application-local.yml 또는 실행 시 JVM 파라미터에
 * -Dapp.run-master-update-on-startup=true 를 추가하세요.
 *
 * 주의: 이 클래스는 로컬/시연용입니다. 실행 후에는 삭제하거나, 운영에서는 property를 false로 설정하세요.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.run-master-update-on-startup", havingValue = "true")
public class OneTimeMasterFileUpdater implements ApplicationRunner {

    private final MasterFileUpdateService masterFileUpdateService;

    @Override
    public void run(ApplicationArguments args) {
        log.info("OneTimeMasterFileUpdater: 시작(조건부 실행)");
        try {
            masterFileUpdateService.updateMasterFiles();
            log.info("OneTimeMasterFileUpdater: 마스터파일 업데이트 완료");
        } catch (Exception e) {
            log.error("OneTimeMasterFileUpdater: 업데이트 중 오류", e);
        }
    }
}
