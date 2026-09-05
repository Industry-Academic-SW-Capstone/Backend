package grit.stockIt.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 시각 조회를 주입 가능한 의존성으로 만든다.
 *
 * <p>{@code LocalDate.now()}를 직접 부르면 그 코드는 실행 시각에 따라 다르게 동작하고,
 * 테스트가 시각을 고정할 수 없다. 장 시간대로 분기하는 차트 조회처럼 시각이 제어 흐름을
 * 바꾸는 곳에서는 {@code Clock}을 주입받아 {@code LocalDate.now(clock)} 형태로 쓴다.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
