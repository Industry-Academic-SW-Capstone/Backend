package grit.stockIt.global.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

@Component
public class LocalTtlCache {

    // 값 길이(문자 수) 합의 상한. 차트 JSON 이 가장 크며, 넘으면 오래 안 쓴 항목부터 버린다.
    private static final long MAX_TOTAL_CHARS = 20_000_000L;

    private final Cache<String, Entry> cache = Caffeine.newBuilder()
            .maximumWeight(MAX_TOTAL_CHARS)
            .weigher((String key, Entry entry) -> key.length() + entry.value().length())
            .expireAfter(new Expiry<String, Entry>() {
                @Override
                public long expireAfterCreate(String key, Entry entry, long currentTime) {
                    return entry.ttl().toNanos();
                }

                @Override
                public long expireAfterUpdate(String key, Entry entry, long currentTime, long currentDuration) {
                    return entry.ttl().toNanos();
                }

                @Override
                public long expireAfterRead(String key, Entry entry, long currentTime, long currentDuration) {
                    return currentDuration;
                }
            })
            .build();

    public Optional<String> get(String key) {
        Entry entry = cache.getIfPresent(key);
        return entry == null ? Optional.empty() : Optional.of(entry.value());
    }

    public void put(String key, String value, Duration ttl) {
        if (ttl.isNegative() || ttl.isZero()) {
            cache.invalidate(key);
            return;
        }
        cache.put(key, new Entry(value, ttl));
    }

    public boolean delete(String key) {
        return cache.asMap().remove(key) != null;
    }

    public boolean contains(String key) {
        return cache.getIfPresent(key) != null;
    }

    public Optional<Duration> remainingTtl(String key) {
        return cache.policy().expireVariably().flatMap(policy -> policy.getExpiresAfter(key));
    }

    private record Entry(String value, Duration ttl) {
    }
}
