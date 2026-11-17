package grit.stockIt.domain.matching.service;

public sealed interface FillDispatchResult {

    // 큐에 기록됐다. 체결은 워커가 나중에 한다.
    record Queued(int partition, long offset) implements FillDispatchResult {
    }

    // 큐에 넣지 못했다. 이 이벤트는 사라진다.
    record Failed() implements FillDispatchResult {
    }
}
