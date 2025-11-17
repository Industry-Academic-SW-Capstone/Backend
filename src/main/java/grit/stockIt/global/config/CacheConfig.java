package grit.stockIt.global.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Caffeine 로컬 캐시 설정
 * - 랭킹 데이터를 메모리에 캐싱하여 DB 부하 감소
 * - 1분마다 스케줄러가 갱신하므로 TTL은 70초로 설정
 */
@Configuration
@EnableCaching
public class CacheConfig {

    /**
     * Caffeine 캐시 매니저 설정
     *
     * 캐시 스펙:
     * - maximumSize: 최대 100개 엔트리 (Main + 대회들)
     * - expireAfterWrite: 70초 후 자동 만료 (스케줄러 주기 1분 + 여유 10초)
     * - recordStats: 캐시 통계 수집 (히트율, 미스율 등)
     *
     * 캐시 키 예시:
     * - "main:balance" (Main 계좌 잔액 랭킹)
     * - "contest:1:balance" (대회 1 잔액 랭킹)
     * - "contest:1:returnRate" (대회 1 수익률 랭킹)
     *
     * @return CacheManager
     */
    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager("rankings");

        cacheManager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(100) // 최대 100개 캐시 엔트리
                .expireAfterWrite(70, TimeUnit.SECONDS) // 70초 후 만료
                .recordStats()); // 캐시 통계 수집

        return cacheManager;
    }
}

