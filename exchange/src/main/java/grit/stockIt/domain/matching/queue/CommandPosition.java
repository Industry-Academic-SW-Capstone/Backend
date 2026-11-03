package grit.stockIt.domain.matching.queue;

// 큐에서 명령이 기록된 위치. 중복 판정의 기준이다.
public record CommandPosition(String topic, int partition, long offset) {
}
