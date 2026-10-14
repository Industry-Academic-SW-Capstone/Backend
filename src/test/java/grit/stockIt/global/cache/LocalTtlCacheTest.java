package grit.stockIt.global.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class LocalTtlCacheTest {

    private final LocalTtlCache cache = new LocalTtlCache();

    @Test
    @DisplayName("저장한 값을 돌려주고 남은 TTL 은 저장한 TTL 이하다")
    void put_thenGet_returnsValueWithRemainingTtl() {
        cache.put("k", "v", Duration.ofMinutes(5));

        assertThat(cache.get("k")).contains("v");
        assertThat(cache.contains("k")).isTrue();
        assertThat(cache.remainingTtl("k")).hasValueSatisfying(ttl ->
                assertThat(ttl).isLessThanOrEqualTo(Duration.ofMinutes(5)).isGreaterThan(Duration.ofMinutes(4)));
    }

    @Test
    @DisplayName("키마다 TTL 이 따로 적용된다")
    void ttl_isPerEntry() {
        cache.put("short", "v", Duration.ofSeconds(30));
        cache.put("long", "v", Duration.ofHours(12));

        assertThat(cache.remainingTtl("short").orElseThrow()).isLessThanOrEqualTo(Duration.ofSeconds(30));
        assertThat(cache.remainingTtl("long").orElseThrow()).isGreaterThan(Duration.ofHours(11));
    }

    @Test
    @DisplayName("TTL 이 0 이하면 저장하지 않고 기존 값도 지운다")
    void nonPositiveTtl_removesEntry() {
        cache.put("k", "v", Duration.ofMinutes(5));

        cache.put("k", "v2", Duration.ZERO);

        assertThat(cache.get("k")).isEmpty();
    }

    @Test
    @DisplayName("삭제는 값이 있었는지를 돌려준다")
    void delete_reportsWhetherEntryExisted() {
        cache.put("k", "v", Duration.ofMinutes(5));

        assertThat(cache.delete("k")).isTrue();
        assertThat(cache.delete("k")).isFalse();
        assertThat(cache.get("k")).isEmpty();
    }
}
