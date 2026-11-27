package grit.stockIt.domain.matching.service;

import java.time.Clock;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class FillIdGenerator {

    public static final long EPOCH_MILLIS = 1767225600000L;
    public static final int PARTITION_COUNT = 256;

    private static final int SEQ_BITS = 6;
    private static final int PARTITION_BITS = 8;
    private static final int MILLIS_SHIFT = SEQ_BITS + PARTITION_BITS;
    private static final long SEQ_MASK = (1L << SEQ_BITS) - 1;
    private static final long PARTITION_MASK = (1L << PARTITION_BITS) - 1;

    private final Clock clock;
    private final ConcurrentHashMap<Integer, State> states = new ConcurrentHashMap<>();

    public FillIdGenerator(Clock clock) {
        this.clock = clock;
    }

    public long next(int partition) {
        State state = stateOf(partition);
        synchronized (state) {
            long ms = Math.max(clock.millis() - EPOCH_MILLIS, state.lastMillis);
            long seq = 0;
            if (ms == state.lastMillis) {
                seq = state.lastSeq + 1;
                if (seq > SEQ_MASK) {
                    // 시계를 기다리지 않고 논리 ms 를 앞당긴다
                    ms++;
                    seq = 0;
                }
            }
            state.lastMillis = ms;
            state.lastSeq = seq;
            long id = compose(ms, partition, seq);
            state.issuedMax = Math.max(state.issuedMax, id);
            return id;
        }
    }

    public void observe(int partition, long persistedLastFillId) {
        State state = stateOf(partition);
        synchronized (state) {
            long ms = persistedLastFillId >>> MILLIS_SHIFT;
            long seq = persistedLastFillId & SEQ_MASK;
            if (ms > state.lastMillis || (ms == state.lastMillis && seq > state.lastSeq)) {
                state.lastMillis = ms;
                state.lastSeq = seq;
            }
        }
    }

    public OptionalLong takeIssuedSinceLastTake(int partition) {
        State state = stateOf(partition);
        synchronized (state) {
            if (state.issuedMax < 0) {
                return OptionalLong.empty();
            }
            long max = state.issuedMax;
            state.issuedMax = -1;
            return OptionalLong.of(max);
        }
    }

    public static int partitionOf(long id) {
        return (int) ((id >>> SEQ_BITS) & PARTITION_MASK);
    }

    public static long millisOf(long id) {
        return id >>> MILLIS_SHIFT;
    }

    private State stateOf(int partition) {
        if (partition < 0 || partition >= PARTITION_COUNT) {
            throw new IllegalArgumentException("파티션 번호가 범위를 벗어났다: " + partition);
        }
        return states.computeIfAbsent(partition, p -> new State());
    }

    private static long compose(long ms, int partition, long seq) {
        return (ms << MILLIS_SHIFT) | ((long) partition << SEQ_BITS) | seq;
    }

    private static final class State {
        private long lastMillis = -1;
        private long lastSeq = SEQ_MASK;
        private long issuedMax = -1;
    }
}
