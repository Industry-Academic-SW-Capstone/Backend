package grit.stockIt.global.outbox;

public record OutboxRecord(String topic, String key, Object payload) {
}
