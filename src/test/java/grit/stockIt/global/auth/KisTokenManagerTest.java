package grit.stockIt.global.auth;

import grit.stockIt.global.auth.entity.KisCredentialType;
import grit.stockIt.global.auth.repository.KisCredentialRepository;
import grit.stockIt.global.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

// 실제 KIS API를 호출해 토큰 발급/저장을 검증하는 외부 연동 테스트. KIS_API_APPKEY 가 있을 때만 돈다.
@EnabledIfEnvironmentVariable(named = "KIS_API_APPKEY", matches = ".+",
        disabledReason = "실제 KIS API 키(KIS_API_APPKEY)가 있을 때만 실행되는 외부 연동 테스트")
class KisTokenManagerTest extends IntegrationTestSupport {

    @Autowired
    private KisTokenManager kisTokenManager;

    @Autowired
    private KisCredentialRepository kisCredentialRepository;

    @Test
    @DisplayName("AccessToken을 발급받아 DB에 저장하고, 다시 요청하면 같은 토큰을 돌려준다")
    void getAccessTokenTest() {
        kisTokenManager.delete(KisCredentialType.ACCESS_TOKEN);

        String token1 = kisTokenManager.getAccessToken();

        assertThat(token1).isNotNull().isNotEmpty();
        assertThat(kisCredentialRepository.findById(KisCredentialType.ACCESS_TOKEN))
                .hasValueSatisfying(credential -> assertThat(credential.getValue()).isEqualTo(token1));

        String token2 = kisTokenManager.getAccessToken();

        assertThat(token2).isEqualTo(token1);
    }
}
