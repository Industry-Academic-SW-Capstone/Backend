package grit.stockIt.domain.llm.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;

/**
 * 기업 설명 Redis 캐시 Repository
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class CompanyDescriptionCacheRepository {

    private static final String CACHE_KEY_PATTERN = "company_desc:%s";
    private static final Duration TTL = Duration.ofHours(3); // 3시간

    private final StringRedisTemplate redisTemplate;

    /**
     * 캐시에서 기업 설명 조회
     */
    public Optional<String> get(String companyName) {
        try {
            String key = String.format(CACHE_KEY_PATTERN, companyName);
            String cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                log.debug("캐시 히트: companyName={}", companyName);
            }
            return Optional.ofNullable(cached);
        } catch (Exception e) {
            log.error("Redis 캐시 조회 실패 (무시): companyName={}", companyName, e);
            return Optional.empty();
        }
    }

    /**
     * 기업 설명을 캐시에 저장
     */
    public void save(String companyName, String description) {
        try {
            String key = String.format(CACHE_KEY_PATTERN, companyName);
            redisTemplate.opsForValue().set(key, description, TTL);
            log.debug("캐시 저장: companyName={}, TTL={}시간", companyName, TTL.toHours());
        } catch (Exception e) {
            log.error("Redis 캐시 저장 실패 (무시): companyName={}", companyName, e);
        }
    }
}

