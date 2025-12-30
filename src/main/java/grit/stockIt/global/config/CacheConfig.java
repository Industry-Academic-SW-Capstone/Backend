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
 * - 1분마다 스케줄러가 갱신
 */
@Configuration
@EnableCaching
public class CacheConfig {

    /**
     * Caffeine 캐시 매니저 설정
     * 
     * 캐시 스펙:
     * - TTL: 60초 (1분)
     * - 최대 크기: 100개 엔트리 (Main + 대회들)
     * 
     * @return CacheManager
     */
    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager("rankings");
        
        cacheManager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(60, TimeUnit.SECONDS) // TTL 60초
                .maximumSize(100)); // 최대 100개 캐시
        
        return cacheManager;
    }
}

