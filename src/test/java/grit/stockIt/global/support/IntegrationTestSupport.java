package grit.stockIt.global.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Testcontainers 기반 통합 테스트 공용 베이스 클래스.
 * PostgreSQL/Redis 컨테이너를 자동으로 띄우므로 로컬 DB 없이 어디서든(CI 포함) 실행 가능하다.
 * DB가 필요한 @SpringBootTest 는 이 클래스를 상속한다.
 *
 * 컨테이너는 싱글턴 패턴으로 JVM당 1회만 기동한다. @Testcontainers/@Container 확장은
 * 클래스마다 컨테이너를 정지·재시작해 새 포트를 할당하는데, 캐시된 Spring 컨텍스트는
 * 최초 포트를 유지하므로 컨텍스트를 공유하는 두 번째 테스트 클래스부터 연결이 깨진다.
 * (withReuse(true)는 ~/.testcontainers.properties의 testcontainers.reuse.enable=true와
 * 결합해 gradle 실행 간 컨테이너 재사용까지 허용한다.)
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.task.scheduling.enabled=false")
public abstract class IntegrationTestSupport {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:15"))
            .withDatabaseName("test_database")
            .withUsername("test_user")
            .withPassword("test_password")
            .withReuse(true);

    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7"))
            .withExposedPorts(6379)
            .withReuse(true);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void configureContainers(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");

        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", REDIS::getFirstMappedPort);
    }
}
