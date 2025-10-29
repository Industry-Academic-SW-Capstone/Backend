package grit.stockIt.global.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
    "kis.api.appkey=${KIS_API_APPKEY:test_appkey}",
    "kis.api.appsecret=${KIS_API_APPSECRET:test_appsecret}"
})
class KisTokenManagerTest {

    @Autowired
    private KisTokenManager kisTokenManager;

    @Autowired
    private StringRedisTemplate redisTemplate; // Redis를 직접 확인하기 위해 주입

    @BeforeEach
    void setUp() {
        // 환경변수 체크
        String appkey = System.getenv("KIS_API_APPKEY");
        String appsecret = System.getenv("KIS_API_APPSECRET");
        
        if (appkey == null || appkey.isEmpty() || appsecret == null || appsecret.isEmpty()) {
            System.out.println("⚠환경변수 KIS_API_APPKEY 또는 KIS_API_APPSECRET가 설정되지 않았습니다.");
            System.out.println("테스트용 더미 값으로 실행됩니다.");
        } else {
            System.out.println("환경변수에서 실제 API 키를 사용합니다.");
        }
    }

    @Test
    @DisplayName("AccessToken을 성공적으로 발급받고 Redis에 저장한다")
    void getAccessTokenTest() {
        // 1. Redis를 깨끗하게 비운다 (테스트를 위해)
        redisTemplate.delete("kis:access_token");

        // 2. 토큰을 처음 요청한다 (이때 KIS 서버에 접속해야 함)
        System.out.println("--- 1번째 요청 ---");
        String token1 = kisTokenManager.getAccessToken();

        // 3. 토큰이 null이 아니고 비어있지 않은지 확인
        assertThat(token1).isNotNull().isNotEmpty();
        System.out.println("발급받은 토큰: " + token1);

        // 4. Redis에 정말 저장되었는지 확인
        String cachedToken = redisTemplate.opsForValue().get("kis:access_token");
        assertThat(cachedToken).isEqualTo(token1);
        System.out.println("Redis에 저장된 토큰: " + cachedToken);

        // 5. 토큰을 다시 요청한다 (이때는 KIS 서버가 아닌 Redis에서 가져와야 함)
        System.out.println("--- 2번째 요청 ---");
        String token2 = kisTokenManager.getAccessToken();

        // 6. 1번 토큰과 2번 토큰이 같은지 확인 (같아야 Redis 캐시가 동작한 것)
        assertThat(token2).isEqualTo(token1);
        System.out.println("캐시에서 가져온 토큰: " + token2);
    }
}