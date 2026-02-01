package grit.stockIt.global.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * 테스트 환경에서 스케줄러를 비활성화하는 설정 클래스
 * application-test.yml의 spring.task.scheduling.enabled=false와 함께 사용
 */
@Configuration
@ConditionalOnProperty(name = "spring.task.scheduling.enabled", havingValue = "false", matchIfMissing = false)
public class TestSchedulingConfig implements SchedulingConfigurer {

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        // 스케줄러를 완전히 비활성화
        // Scheduler를 null로 설정하여 스케줄러가 실행되지 않도록 함
        taskRegistrar.setScheduler(null);
    }
}

