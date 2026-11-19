package grit.stockIt.domain.matching.queue;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

// 거래소 체결 워커의 지표. 넣는 쪽(시세)의 전송 결과는 FillCommandMetrics 가 센다.
// 프레임워크가 재지 못하는 것만 둔다 — 메시지 안의 시각부터의 경과와 워터마크 판정. 처리 시간·횟수·실패는
// spring.kafka.listener 가, DLT 적재 수는 kafka-exporter 의 DLT 토픽 offset 이 보여 준다.
// 시각은 모두 같은 JVM 시계라 넣은 쪽과 꺼낸 쪽 사이에 시계 오차가 없다.
@Component
public class MatchingQueueMetrics {

    private final Timer queueWait;
    private final Timer endToEnd;
    private final Counter duplicate;

    public MatchingQueueMetrics(MeterRegistry registry) {
        this.queueWait = Timer.builder("matching.fill.queue.wait")
                .description("큐에 넣은 뒤 워커가 꺼낼 때까지").register(registry);
        this.endToEnd = Timer.builder("matching.fill.e2e")
                .description("큐에 넣은 뒤 체결이 커밋될 때까지").register(registry);
        this.duplicate = Counter.builder("matching.fill.duplicate")
                .description("이미 반영한 위치라 건너뛴 명령 수(재전달)").register(registry);
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

    private Duration elapsedSince(long epochMillis) {
        return Duration.ofMillis(Math.max(0, System.currentTimeMillis() - epochMillis));
    }
}
