package grit.stockIt.domain.matching.queue;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

// 시세가 체결 명령을 matching.commands 에 넣은 결과. 넣는 쪽(시세)의 지표라 거래소 지표(MatchingQueueMetrics)와 나눈다.
// 이름은 그대로 둔다 — 대시보드가 이 이름을 본다.
@Component
public class FillCommandMetrics {

    private final Counter enqueued;
    private final Counter enqueueFailed;

    public FillCommandMetrics(MeterRegistry registry) {
        this.enqueued = Counter.builder("matching.enqueue").tag("result", "ok")
                .description("체결 명령을 큐에 넣은 수").register(registry);
        // spring.kafka.template 은 다른 전송과 섞여 있어 체결 명령 유실을 따로 본다.
        this.enqueueFailed = Counter.builder("matching.enqueue").tag("result", "fail")
                .description("체결 명령을 큐에 넣지 못한 수 — 유실").register(registry);
    }

    public void enqueued(boolean succeeded) {
        (succeeded ? enqueued : enqueueFailed).increment();
    }
}
