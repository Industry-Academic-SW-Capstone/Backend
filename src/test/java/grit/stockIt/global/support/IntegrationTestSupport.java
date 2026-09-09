package grit.stockIt.global.support;

import grit.stockIt.domain.contest.service.DefaultContestInitializer;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.transaction.TestTransaction;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

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

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DefaultContestInitializer defaultContestInitializer;

    @DynamicPropertySource
    static void configureContainers(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");

        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", REDIS::getFirstMappedPort);
    }

    /**
     * 테스트가 커밋한 데이터를 매 메서드 후 지우고, 기동 시점 상태로 되돌린다.
     *
     * <p><b>왜 필요한가:</b> 통합 테스트 다수가 {@code PROPAGATION_REQUIRES_NEW}로 실제 커밋을 한다
     * (afterCommit 훅과 동시성 검증에 진짜 커밋이 필요하기 때문). 여기에 컨테이너 재사용
     * ({@code withReuse(true)})과 {@code ddl-auto: update}가 겹쳐, 지우지 않으면 데이터가
     * gradle 실행 간에도 영구히 누적된다.
     *
     * <p>실제로 6주간 누적된 결과 계좌 보유 종목이 598개까지 늘었고, 랭킹 배치가 그 종목들의
     * 현재가를 KIS 속도 제한(초당 25건)에 맞춰 순차 조회하면서 테스트 메서드 하나가 24초씩
     * 잠들었다. 빌드가 20분을 넘긴 주된 원인이었다.
     *
     * <p><b>왜 {@code @AfterEach}인가:</b> {@code @Sql(BEFORE_TEST_METHOD)}은 {@code @BeforeEach}보다
     * <b>먼저</b> 실행된다. 앞에서 지우면 미션 테스트들이 불러온 {@code data.sql} 시드가 날아간다.
     * 뒤에서 지우면 다음 테스트의 {@code @Sql}이 다시 심는다.
     *
     * <p>{@code @Transactional} 테스트는 건너뛴다. Spring이 롤백하므로 남길 데이터가 없고,
     * 영속성 컨텍스트가 열린 상태에서 정리하면 세션 캐시와 충돌한다.
     */
    @AfterEach
    void resetDatabaseToStartupState() {
        if (TestTransaction.isActive()) {
            // @Transactional 테스트는 Spring이 롤백하므로 커밋된 데이터가 없다.
            // 게다가 영속성 컨텍스트가 아직 열려 있어, JdbcTemplate으로 TRUNCATE(Hibernate 우회)한 뒤
            // 같은 식별자를 재발급하면 세션에 남은 엔티티와 충돌한다(DuplicateKeyException).
            return;
        }
        truncateAllTables();
        restoreDefaultContest();
    }

    private void truncateAllTables() {
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT tablename FROM pg_tables WHERE schemaname = 'public'", String.class);
        if (tables.isEmpty()) {
            return;
        }
        jdbcTemplate.execute(
                "TRUNCATE TABLE " + String.join(", ", tables) + " RESTART IDENTITY CASCADE");
    }

    /**
     * 기본 대회를 다시 심는다.
     *
     * <p>{@link DefaultContestInitializer}는 {@code ApplicationRunner}라 <b>앱 기동 시 한 번만</b>
     * 실행된다. 테스트 사이에 컨텍스트가 재기동되지 않으므로, TRUNCATE 후 여기서 복원하지 않으면
     * 이후 모든 테스트가 {@code "Default contest not found"}로 깨진다
     * ({@code AccountService}가 회원 가입 시 기본 대회를 요구한다).
     *
     * <p>INSERT를 직접 쓰지 않고 초기화 로직을 호출하는 이유: 기본 대회의 정의(시드머니·수수료 등)가
     * 바뀌어도 테스트가 자동으로 따라가게 하기 위함이다.
     */
    private void restoreDefaultContest() {
        try {
            defaultContestInitializer.run(new DefaultApplicationArguments());
        } catch (Exception e) {
            throw new IllegalStateException("기본 대회 복원 실패", e);
        }
    }
}
