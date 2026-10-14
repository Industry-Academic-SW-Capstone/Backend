package grit.stockIt.domain.llm.repository;

import grit.stockIt.global.cache.LocalTtlCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;

@Slf4j
@Repository
@RequiredArgsConstructor
public class CompanyDescriptionCacheRepository {

    private static final String CACHE_KEY_PATTERN = "company_desc:%s";
    private static final Duration TTL = Duration.ofHours(3); // 3시간

    private final LocalTtlCache cache;

    public Optional<String> get(String companyName) {
        Optional<String> cached = cache.get(CACHE_KEY_PATTERN.formatted(companyName));
        if (cached.isPresent()) {
            log.debug("캐시 히트: companyName={}", companyName);
        }
        return cached;
    }

    public void save(String companyName, String description) {
        cache.put(CACHE_KEY_PATTERN.formatted(companyName), description, TTL);
        log.debug("캐시 저장: companyName={}, TTL={}시간", companyName, TTL.toHours());
    }
}
