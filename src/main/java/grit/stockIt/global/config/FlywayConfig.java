package grit.stockIt.global.config;

import jakarta.annotation.PostConstruct;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
public class FlywayConfig {

    @Autowired
    private DataSource dataSource;

    /**
     * Flyway를 JPA 초기화 전에 명시적으로 실행
     * 순환 의존성 문제 해결
     */
    @PostConstruct
    public void migrateFlyway() {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true) // 기존 스키마를 baseline으로 인식
                .validateOnMigrate(false) // 기존 스키마와 충돌 방지
                .load();
        
        // Flyway 스키마 히스토리가 없으면 baseline 생성
        try {
            if (!flyway.info().current().isVersioned()) {
                flyway.baseline();
            }
        } catch (Exception e) {
            // baseline이 이미 존재하는 경우 무시
        }
        
        // 마이그레이션 실행 (실패 시 애플리케이션 시작 중단 - 안전)
        flyway.migrate();
    }
}

