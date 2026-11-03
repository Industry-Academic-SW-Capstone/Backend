package grit.stockIt.global.auth;

import grit.stockIt.global.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 KIS API를 호출해 토큰 발급/캐싱을 검증하는 외부 연동 테스트.
 * 실제 API 키가 환경변수(KIS_API_APPKEY)로 설정된 경우에만 실행되고,
 * 그 외(CI 포함)에는 자동으로 skip 된다.
 */
@EnabledIfEnvironmentVariable(named = "KIS_API_APPKEY", matches = ".+",
        disabledReason = "실제 KIS API 키(KIS_API_APPKEY)가 있을 때만 실행되는 외부 연동 테스트")
class KisTokenManagerTest extends IntegrationTestSupport {

    @Autowired
    private KisTokenManager kisTokenManager;

    @Autowired
    private StringRedisTemplate redisTemplate; // Redis를 직접 확인하기 위해 주입

    @Test
    @DisplayName("AccessToken을 성공적으로 발급받고 Redis에 저장한다")
    void getAccessTokenTest() {
        // 1. Redis를 깨끗하게 비운다 (테스트를 위해)
        redisTemplate.delete("kis:access_token");

        // 2. 토큰을 처음 요청한다 (이때 KIS 서버에 접속해야 함)
        String token1 = kisTokenManager.getAccessToken();

        // 3. 토큰이 null이 아니고 비어있지 않은지 확인
        assertThat(token1).isNotNull().isNotEmpty();

        // 4. Redis에 정말 저장되었는지 확인
        String cachedToken = redisTemplate.opsForValue().get("kis:access_token");
        assertThat(cachedToken).isEqualTo(token1);

        // 5. 토큰을 다시 요청한다 (이때는 KIS 서버가 아닌 Redis에서 가져와야 함)
        String token2 = kisTokenManager.getAccessToken();

        // 6. 1번 토큰과 2번 토큰이 같은지 확인 (같아야 Redis 캐시가 동작한 것)
        assertThat(token2).isEqualTo(token1);
    }
}
