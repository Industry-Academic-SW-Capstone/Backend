package grit.stockIt.domain.matching.queue;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

// 거래소 체결 워커의 지표. 넣는 쪽(시세)의 전송 결과는 FillCommandMetrics 가 센다.
// 프레임워크가 재지 못하는 것만 둔다 — 메시지 안의 시각부터의 경과, 워터마크 판정, 배치로 묶인 명령 하나당 시간과 묶음 크기.
// 리스너 호출 횟수·실패는 spring.kafka.listener 가, DLT 적재 수는 kafka-exporter 의 DLT 토픽 offset 이 보여 준다.
// 시각은 모두 같은 JVM 시계라 넣은 쪽과 꺼낸 쪽 사이에 시계 오차가 없다.
@Component
public class MatchingQueueMetrics {

    private final Timer queueWait;
    private final Timer endToEnd;
    private final Counter duplicate;
    private final DistributionSummary batchSize;
    private final Counter batchFallback;
    private final Map<MatchingCommandType, Timer> commandDuration = new EnumMap<>(MatchingCommandType.class);

    public MatchingQueueMetrics(MeterRegistry registry) {
        this.queueWait = Timer.builder("matching.fill.queue.wait")
                .description("큐에 넣은 뒤 워커가 꺼낼 때까지").register(registry);
        this.endToEnd = Timer.builder("matching.fill.e2e")
                .description("큐에 넣은 뒤 체결이 커밋될 때까지").register(registry);
        this.duplicate = Counter.builder("matching.fill.duplicate")
                .description("이미 반영한 위치라 건너뛴 명령 수(재전달)").register(registry);
        this.batchSize = DistributionSummary.builder("matching.batch.size")
                .description("트랜잭션 하나로 묶어 처리한 명령 수").register(registry);
        this.batchFallback = Counter.builder("matching.batch.fallback")
                .description("묶음 처리에 실패해 한 건씩 다시 처리한 횟수").register(registry);
        for (MatchingCommandType type : MatchingCommandType.values()) {
            commandDuration.put(type, Timer.builder("matching.command.duration")
                    .description("명령 하나당 처리 시간(묶음 처리 시간 ÷ 명령 수)").tag("type", type.name())
                    .register(registry));
        }
    }

    public void pickedUp(long enqueuedAt, long pickedUpAt) {
        queueWait.record(Duration.ofMillis(Math.max(0, pickedUpAt - enqueuedAt)));
    }

    public void filled(long enqueuedAt) {
        endToEnd.record(elapsedSince(enqueuedAt));
    }

    public void skippedAsDuplicate() {
        duplicate.increment();
    }

    public void batch(int size) {
        batchSize.record(size);
    }

    public void batchFallback() {
        batchFallback.increment();
    }

    public void commandTime(MatchingCommandType type, long nanos) {
        commandDuration.get(type).record(nanos, TimeUnit.NANOSECONDS);
    }

    private Duration elapsedSince(long epochMillis) {
        return Duration.ofMillis(Math.max(0, System.currentTimeMillis() - epochMillis));
    }
}
