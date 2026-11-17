package grit.stockIt.global.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// V20(서비스별 schema)과 db/service-role.sql 을 운영처럼 "public 에 모든 표가 있는 DB" 에 돌려 보고,
// 나뉜 schema 하나를 그 서비스만의 DB(인스턴스 대신 새 database)로 옮겨 본다.
// 테스트 DB 의 public 스키마(엔티티로 만든 것)를 새 database 로 복제해 그 위에서 실행한다 — 다른 테스트의 DB 는 건드리지 않는다.
@DisplayName("서비스별 DB 로 나누기 (V20 + 서비스 계정 + schema 옮기기)")
class ServiceSchemaMigrationTest extends IntegrationTestSupport {

    private static final String DB = "v20_check";
    private static final String BROKER_DB = "broker_instance";
    private static final String[] ROLES = {"stockit_exchange", "stockit_broker", "stockit_extra"};

    @BeforeEach
    void cloneSchemaIntoFreshDatabase() throws Exception {
        psql("postgres", "DROP DATABASE IF EXISTS " + DB);
        psql("postgres", "DROP DATABASE IF EXISTS " + BROKER_DB);
        psql("postgres", "DROP ROLE IF EXISTS " + String.join(", ", ROLES));
        psql("postgres", "CREATE DATABASE " + DB);
        // public 만 복제한다. 같은 컨테이너의 서비스별 기동 테스트가 만든 schema 는 옮길 대상이 아니다.
        sh("pg_dump -U test_user --schema-only --schema=public test_database | psql -q -U test_user -d " + DB);
        // 옛 DB 에만 있는 것: 서비스를 넘는 외래키, 회원의 알림 설정 컬럼
        psql(DB, "ALTER TABLE member_title ADD CONSTRAINT fk_member_title_member "
                + "FOREIGN KEY (member_id) REFERENCES member(member_id)");
        psql(DB, "ALTER TABLE member ADD COLUMN IF NOT EXISTS fcm_token VARCHAR(1000)");
        psql(DB, "INSERT INTO processed_event (consumer, event_id, processed_at) VALUES "
                + "('broker', 'e-broker', now()), ('mission', 'e-mission', now())");
        POSTGRES.copyFileToContainer(MountableFile.forClasspathResource("db/migration/V20__split_service_schemas.sql"),
                "/tmp/v20.sql");
        POSTGRES.copyFileToContainer(MountableFile.forHostPath(Path.of("../db/service-role.sql")), "/tmp/role.sql");
    }

    @Test
    @DisplayName("표가 서비스 schema 로 옮겨지고, 아웃박스·처리 기록이 서비스마다 생기고, 서비스를 넘는 외래키와 옛 컬럼이 지워진다")
    void splitsTablesBySchema() throws Exception {
        runFile("/tmp/v20.sql");

        try (Connection admin = connect(POSTGRES.getUsername(), POSTGRES.getPassword())) {
            assertThat(exists(admin, "public.member")).isFalse();
            assertThat(exists(admin, "broker.member")).isTrue();
            assertThat(exists(admin, "broker.trade_order")).isTrue();
            assertThat(exists(admin, "exchange.exchange_order")).isTrue();
            assertThat(exists(admin, "extra.mission_progress")).isTrue();
            for (String schema : new String[] {"exchange", "broker", "extra"}) {
                assertThat(exists(admin, schema + ".outbox_message")).as(schema).isTrue();
                assertThat(exists(admin, schema + ".processed_event")).as(schema).isTrue();
            }
            assertThat(single(admin, "SELECT string_agg(consumer, ',') FROM broker.processed_event")).isEqualTo("broker");
            assertThat(single(admin, "SELECT string_agg(consumer, ',') FROM extra.processed_event")).isEqualTo("mission");
            assertThat(single(admin, "SELECT count(*)::text FROM pg_constraint WHERE conname = 'fk_member_title_member'"))
                    .isEqualTo("0");
            assertThat(single(admin, "SELECT count(*)::text FROM information_schema.columns "
                    + "WHERE table_schema = 'broker' AND table_name = 'member' AND column_name = 'fcm_token'"))
                    .isEqualTo("0");
            // 같은 서비스 안의 외래키는 남는다 (회원 칭호 → 칭호)
            assertThat(single(admin, "SELECT count(*)::text FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid "
                    + "JOIN pg_namespace n ON n.oid = t.relnamespace WHERE c.contype = 'f' AND n.nspname = 'extra' "
                    + "AND t.relname = 'member_title'")).isNotEqualTo("0");
        }
    }

    @Test
    @DisplayName("나뉜 schema 를 그 서비스만의 DB 로 옮기면, 서비스 계정은 자기 표만 있는 DB 를 기본 schema 로 본다")
    void movesSchemaIntoServiceOwnDatabase() throws Exception {
        runFile("/tmp/v20.sql");
        psql("postgres", "CREATE DATABASE " + BROKER_DB);
        sh("psql -q -v ON_ERROR_STOP=1 -U test_user -d " + BROKER_DB
                + " -v service=broker -v password=pw -f /tmp/role.sql");
        // 새 인스턴스에서는 그 서비스 계정으로 복원한다 — 표가 그 계정 소유가 된다.
        // schema 는 계정 스크립트가 이미 만들었으므로 덤프의 CREATE SCHEMA 줄은 뺀다.
        sh("pg_dump -U test_user --schema=broker --no-owner --no-privileges " + DB
                + " | grep -v '^CREATE SCHEMA' | psql -q -v ON_ERROR_STOP=1 -U stockit_broker -d " + BROKER_DB);

        try (Connection broker = connect(BROKER_DB, "stockit_broker", "pw")) {
            assertThat(single(broker, "SELECT current_schema()")).isEqualTo("broker");
            assertThat(single(broker, "SELECT count(*)::text FROM member")).isNotNull();
            assertThat(single(broker, "SELECT string_agg(consumer, ',') FROM processed_event")).isEqualTo("broker");
            assertThat(exists(broker, "extra.mission")).isFalse();
            assertThat(single(broker, "SELECT tableowner FROM pg_tables WHERE schemaname = 'broker' AND tablename = 'member'"))
                    .isEqualTo("stockit_broker");
        }
    }

    @Test
    @DisplayName("서비스 계정은 다른 서비스의 표를 읽지 못한다(한 인스턴스에 둔 과도기)")
    void serviceRoleCannotReadOtherSchema() throws Exception {
        runFile("/tmp/v20.sql");
        sh("psql -q -v ON_ERROR_STOP=1 -U test_user -d " + DB + " -v service=broker -v password=pw -f /tmp/role.sql");

        try (Connection broker = connect(DB, "stockit_broker", "pw")) {
            assertThat(single(broker, "SELECT current_schema()")).isEqualTo("broker");
            assertThatThrownBy(() -> single(broker, "SELECT count(*) FROM extra.mission"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("permission denied");
        }
    }

    @Test
    @DisplayName("아웃박스에 보내지 않은 메시지가 있으면 아무것도 옮기지 않고 멈춘다")
    void refusesWhenOutboxNotEmpty() throws Exception {
        psql(DB, "INSERT INTO outbox_message (topic, message_key, payload, created_at) VALUES ('t', 'k', '{}', now())");

        Container.ExecResult result = POSTGRES.execInContainer(
                "psql", "-q", "-v", "ON_ERROR_STOP=1", "-U", "test_user", "-d", DB, "-f", "/tmp/v20.sql");

        assertThat(result.getExitCode()).isNotZero();
        assertThat(result.getStderr()).contains("아웃박스에 보내지 않은 메시지");
        try (Connection admin = connect(POSTGRES.getUsername(), POSTGRES.getPassword())) {
            assertThat(exists(admin, "public.member")).isTrue();
        }
    }

    private static void runFile(String path) throws Exception {
        sh("psql -q -v ON_ERROR_STOP=1 -U test_user -d " + DB + " -f " + path);
    }

    private static void psql(String database, String sql) throws Exception {
        Container.ExecResult result = POSTGRES.execInContainer(
                "psql", "-q", "-v", "ON_ERROR_STOP=1", "-U", "test_user", "-d", database, "-c", sql);
        assertThat(result.getExitCode()).as(result.getStderr()).isZero();
    }

    private static void sh(String command) throws Exception {
        Container.ExecResult result = POSTGRES.execInContainer("sh", "-c", "set -e; " + command);
        assertThat(result.getExitCode()).as(result.getStderr()).isZero();
    }

    private static Connection connect(String user, String password) throws SQLException {
        return connect(DB, user, password);
    }

    private static Connection connect(String database, String user, String password) throws SQLException {
        String url = POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/" + database);
        return DriverManager.getConnection(url, user, password);
    }

    private static boolean exists(Connection connection, String table) throws SQLException {
        return "true".equals(single(connection, "SELECT (to_regclass('" + table + "') IS NOT NULL)::text"));
    }

    private static String single(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
