package grit.stockIt.testsupport;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

// 서비스 하나가 혼자 뜨는지(빈 연결이 끊기지 않는지) 확인하는 테스트의 기반. 서비스 모듈의 테스트가
// @SpringBootTest(classes = 그 서비스의 실행 클래스)와 그 서비스의 프로필을 더해 상속한다.
// 컨테이너는 app 테스트(IntegrationTestSupport)와 같은 이미지·같은 재사용 설정이다.
@ActiveProfiles("servicetest")
public abstract class ServiceContextTestSupport {

    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"))
            .withDatabaseName("test_database")
            .withUsername("test_user")
            .withPassword("test_password")
            // 테스트 JVM 이 캐시한 스프링 컨텍스트마다 커넥션 풀을 잡고 있어 기본 100 으로는 모자란다(too many clients).
            .withCommand("postgres", "-c", "max_connections=300")
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

    // 같은 컨테이너에 빈 DB 를 새로 만들고 JDBC URL 을 돌려준다. 컨테이너를 재사용하므로 지난 실행의 DB 는 지우고 만든다.
    protected static String freshDatabaseUrl(String name) {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)");
            statement.execute("CREATE DATABASE " + name);
        } catch (SQLException e) {
            throw new IllegalStateException("테스트 DB 를 만들지 못했습니다: " + name, e);
        }
        return POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/" + name);
    }

    // DB 없이 뜨는 서비스(시세)도 이 기반을 쓰므로 있을 때만 꺼낸다.
    @Autowired
    private ObjectProvider<JdbcTemplate> jdbcTemplate;

    // 커넥션이 그 서비스의 schema 를 보고(search_path), 엔티티 표가 그 schema 에 만들어졌는지 확인한다.
    protected void assertUsesOwnSchema(String schema, String ownTable) {
        assertThat(jdbcTemplate.getObject().queryForObject("SELECT current_schema()", String.class)).isEqualTo(schema);
        assertThat(jdbcTemplate.getObject().queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class,
                schema + "." + ownTable)).isTrue();
    }
}
