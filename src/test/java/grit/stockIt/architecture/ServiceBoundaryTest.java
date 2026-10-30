package grit.stockIt.architecture;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

// MSA 로 나눌 네 서비스(시세·거래소·증권사·부가) 사이의 직접 의존을 고정한다. 서비스 사이는 메시지 계약(CONTRACT)으로만 통해야 한다.
// 지금 남은 위반은 service-boundary-violations.txt 에 있다. 새 위반이 생겨도, 고친 위반이 목록에 남아 있어도 실패한다.
// 목록이 비면 Gradle 모듈로 나눌 수 있다. global·job 일부·test 처럼 서비스에 속하지 않는 패키지는 보지 않는다.
@DisplayName("서비스 경계 — 서비스 사이의 직접 의존은 목록에 있는 것만 남아 있다")
class ServiceBoundaryTest {

    private static final String ROOT = "grit.stockIt.";
    private static final String VIOLATIONS_FILE = "/architecture/service-boundary-violations.txt";

    enum Service { MARKET, EXCHANGE, BROKER, EXTRA, CONTRACT }

    // 패키지 이름이 실제 역할과 다른 클래스. 위에서부터 먼저 맞는 것을 쓴다.
    private static final Map<String, Service> CLASS_OVERRIDES = new LinkedHashMap<>();
    private static final Map<String, Service> PACKAGES = new LinkedHashMap<>();

    static {
        CLASS_OVERRIDES.put("domain.matching.queue.MatchingCommand", Service.CONTRACT);
        CLASS_OVERRIDES.put("domain.matching.queue.MatchingCommandType", Service.CONTRACT);
        CLASS_OVERRIDES.put("domain.matching.queue.MatchingTopics", Service.CONTRACT);
        CLASS_OVERRIDES.put("domain.matching.queue.MatchingCommandSender", Service.CONTRACT);
        CLASS_OVERRIDES.put("domain.matching.dto.LimitOrderFillEvent", Service.CONTRACT);
        CLASS_OVERRIDES.put("domain.order.entity.OrderMethod", Service.CONTRACT);
        CLASS_OVERRIDES.put("domain.order.entity.OrderType", Service.CONTRACT);

        CLASS_OVERRIDES.put("domain.matching.event.LimitOrderFillEventListener", Service.MARKET);
        CLASS_OVERRIDES.put("domain.matching.event.LimitOrderFillEventMessage", Service.MARKET);
        CLASS_OVERRIDES.put("domain.matching.queue.KafkaFillCommandPublisher", Service.MARKET);
        CLASS_OVERRIDES.put("domain.matching.service.FillCommandPublisher", Service.MARKET);
        CLASS_OVERRIDES.put("domain.matching.service.FillDispatchResult", Service.MARKET);
        CLASS_OVERRIDES.put("domain.matching.repository.RedisMarketDataRepository", Service.MARKET);

        CLASS_OVERRIDES.put("domain.matching.queue.OrderCommandPublisher", Service.BROKER);

        CLASS_OVERRIDES.put("domain.order.service.OrderCancelService", Service.EXCHANGE);
        CLASS_OVERRIDES.put("domain.order.service.QueuedCancelOutcome", Service.EXCHANGE);

        PACKAGES.put("contract.", Service.CONTRACT);
        PACKAGES.put("global.websocket.", Service.MARKET);
        PACKAGES.put("domain.matching.", Service.EXCHANGE);
        PACKAGES.put("domain.execution.", Service.EXCHANGE);
        for (String broker : new String[] {"order", "settlement", "account", "member", "auth", "contest",
                "ranking", "stock", "llm", "industry"}) {
            PACKAGES.put("domain." + broker + ".", Service.BROKER);
        }
        PACKAGES.put("job.", Service.BROKER);
        for (String extra : new String[] {"mission", "notification", "title"}) {
            PACKAGES.put("domain." + extra + ".", Service.EXTRA);
        }
    }

    @Test
    @DisplayName("서비스 사이의 직접 의존이 위반 목록과 같다")
    void crossServiceDependencies_matchRecordedViolations() throws IOException {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("grit.stockIt");

        Set<String> actual = new TreeSet<>();
        for (JavaClass origin : classes) {
            Optional<Service> from = serviceOf(origin);
            if (from.isEmpty() || from.get() == Service.CONTRACT) {
                continue;
            }
            for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                JavaClass target = dependency.getTargetClass().getBaseComponentType();
                Optional<Service> to = serviceOf(target);
                if (to.isEmpty() || to.get() == from.get() || to.get() == Service.CONTRACT) {
                    continue;
                }
                actual.add(from.get() + " " + shortName(origin) + " -> " + to.get() + " " + shortName(target));
            }
        }

        Set<String> recorded = readRecordedViolations();
        Set<String> added = new TreeSet<>(actual);
        added.removeAll(recorded);
        Set<String> resolved = new TreeSet<>(recorded);
        resolved.removeAll(actual);

        assertThat(added)
                .withFailMessage("서비스 경계를 넘는 새 의존 — 메시지 계약으로 바꾸거나, 의도한 것이면 목록에 추가:%n%s",
                        String.join(System.lineSeparator(), added))
                .isEmpty();
        assertThat(resolved)
                .withFailMessage("해소된 위반 — 목록에서 지울 것:%n%s", String.join(System.lineSeparator(), resolved))
                .isEmpty();
    }

    private static Optional<Service> serviceOf(JavaClass javaClass) {
        String name = topLevelName(javaClass);
        if (!name.startsWith(ROOT)) {
            return Optional.empty();
        }
        String relative = name.substring(ROOT.length());
        Service override = CLASS_OVERRIDES.get(relative);
        if (override != null) {
            return Optional.of(override);
        }
        return PACKAGES.entrySet().stream()
                .filter(entry -> relative.startsWith(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst();
    }

    private static String shortName(JavaClass javaClass) {
        return topLevelName(javaClass).substring(ROOT.length());
    }

    private static String topLevelName(JavaClass javaClass) {
        String name = javaClass.getName();
        int nested = name.indexOf('$');
        return nested < 0 ? name : name.substring(0, nested);
    }

    private static Set<String> readRecordedViolations() throws IOException {
        try (InputStream in = ServiceBoundaryTest.class.getResourceAsStream(VIOLATIONS_FILE)) {
            assertThat(in).as(VIOLATIONS_FILE).isNotNull();
            Set<String> lines = new TreeSet<>();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                String trimmed = line.strip();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    lines.add(trimmed);
                }
            }
            return lines;
        }
    }
}
