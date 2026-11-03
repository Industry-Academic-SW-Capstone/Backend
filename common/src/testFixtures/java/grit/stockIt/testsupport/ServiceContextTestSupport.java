package grit.stockIt.testsupport;

import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

// 서비스 하나가 혼자 뜨는지(빈 연결이 끊기지 않는지) 확인하는 테스트의 기반. 서비스 모듈의 테스트가
// @SpringBootTest(classes = 그 서비스의 실행 클래스)와 그 서비스의 프로필을 더해 상속한다.
// 컨테이너는 app 테스트(IntegrationTestSupport)와 같은 이미지·같은 재사용 설정이다.
@ActiveProfiles("servicetest")
public abstract class ServiceContextTestSupport {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"))
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
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", REDIS::getFirstMappedPort);
    }
}
