package grit.stockIt.domain.matching.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class FillIdGeneratorTest {

    private static final Instant EPOCH = Instant.ofEpochMilli(FillIdGenerator.EPOCH_MILLIS);

    private final MutableClock clock = new MutableClock(EPOCH.plusSeconds(1));
    private final FillIdGenerator generator = new FillIdGenerator(clock);

    @Test
    void ids_increasePerPartition() {
        long previous = generator.next(2);
        long firstMillis = FillIdGenerator.millisOf(previous);
        for (int i = 1; i < 70; i++) {
            long id = generator.next(2);
            assertThat(id).isGreaterThan(previous);
            if (i >= 64) {
                assertThat(FillIdGenerator.millisOf(id)).isEqualTo(firstMillis + 1);
            } else {
                assertThat(FillIdGenerator.millisOf(id)).isEqualTo(firstMillis);
            }
            previous = id;
        }
    }

    @Test
    void layout_matchesSpec() {
        assertThat(generator.next(5)).isEqualTo((1000L << 14) | (5 << 6));
    }

    @Test
    void partitionsAreIndependent() {
        long a = generator.next(0);
        long b = generator.next(1);

        assertThat(a).isNotEqualTo(b);
        assertThat(FillIdGenerator.partitionOf(a)).isEqualTo(0);
        assertThat(FillIdGenerator.partitionOf(b)).isEqualTo(1);
    }

    @Test
    void clockGoesBack_stillIncreases() {
        long before = generator.next(4);
        clock.set(EPOCH.minusSeconds(9));

        assertThat(generator.next(4)).isGreaterThan(before);
    }

    @Test
    void observe_startsAfterPersisted() {
        FillIdGenerator future = new FillIdGenerator(new MutableClock(EPOCH.plusSeconds(61)));
        long persisted = future.next(3);

        generator.observe(3, persisted);

        assertThat(generator.next(3)).isGreaterThan(persisted);
    }

    @Test
    void partitionOutOfRange_throws() {
        assertThatThrownBy(() -> generator.next(256)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> generator.next(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ids_stayBelow2Pow53() {
        clock.set(Instant.parse("2043-01-01T00:00:00Z"));

        assertThat(generator.next(255)).isLessThan(1L << 53);
    }

    @Test
    void take_returnsMaxThenEmpty() {
        assertThat(generator.takeIssuedSinceLastTake(1)).isEmpty();

        generator.next(1);
        long last = generator.next(1);

        assertThat(generator.takeIssuedSinceLastTake(1)).isEqualTo(OptionalLong.of(last));
        assertThat(generator.takeIssuedSinceLastTake(1)).isEmpty();
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
