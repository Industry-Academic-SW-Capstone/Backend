package grit.stockIt.domain.matching.service;

import grit.stockIt.domain.matching.queue.MatchingEventQueue;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 종목별 큐 깊이를 Micrometer Gauge로 노출한다.
 *
 * <p><b>왜 필요한가.</b> 적체는 HTTP 지연·에러율에 나타나지 않는다. 요청은 큐에 넣기만 하고
 * 200을 돌려주기 때문이다. 부하 스크립트가 응답에 실린 큐 길이를 기록하고는 있으나 그 값은
 * 부하 생성기 안에만 남아, 처리량·체결 지연과 같은 시간축에 놓을 수 없다.
 * <b>"큐가 쌓이면서 처리량이 떨어진다"는 인과는 같은 그래프 위에서만 보인다.</b>
 *
 * <p>종목마다 게이지를 하나씩 등록한다. 전체 합은 Prometheus에서 집계한다
 * ({@code sum(matching_queue_depth)}).
 *
 * <p>스크레이프 스레드에서 {@link MatchingEventQueue#size}를 호출하므로 백엔드에 질의가 간다.
 * RDB 구현은 {@code COUNT(*)} 한 번이고 주기가 스크레이프 간격이라 측정에 영향을 주지 않는다.
 * 조회가 실패해도 스크레이프 전체가 깨지지 않도록 예외는 삼킨다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MatchingQueueDepthMetrics {

    private final MatchingEventQueue matchingEventQueue;
    private final MeterRegistry meterRegistry;

    /**
     * 게이지를 등록한 종목.
     *
     * <p>Micrometer는 게이지 대상을 <b>약한 참조</b>로 잡는다. 이 집합이 종목코드 문자열의
     * 강한 참조를 유지하지 않으면 GC 이후 게이지 값이 NaN으로 사라진다.
     */
    private final Set<String> tracked = ConcurrentHashMap.newKeySet();

    /** 해당 종목의 게이지를 등록한다. 이미 등록됐으면 아무 일도 하지 않는다. */
    public void track(String stockCode) {
        if (stockCode == null || !tracked.add(stockCode)) {
            return;
        }
        Gauge.builder("matching.queue.depth", stockCode, this::depth)
                .description("큐에 남아 있는 체결 이벤트 수")
                .tag("stock", stockCode)
                .register(meterRegistry);
    }

    private double depth(String stockCode) {
        try {
            return matchingEventQueue.size(stockCode);
        } catch (Exception e) {
            log.warn("큐 깊이 조회 실패. stockCode={}", stockCode, e);
            return Double.NaN;
        }
    }
}
