package grit.stockIt.global.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Base64;

/**
 * Firebase Admin SDK 초기화 설정
 * Base64로 인코딩된 서비스 계정 키를 사용하여 Firebase Admin SDK를 초기화
 */
@Slf4j
@Configuration
public class FirebaseConfig {

    private final FirebaseProperties firebaseProperties;

    public FirebaseConfig(FirebaseProperties firebaseProperties) {
        this.firebaseProperties = firebaseProperties;
    }

    @Bean
    public FirebaseApp firebaseApp() throws IOException {
        // 이미 초기화된 FirebaseApp이 있으면 재초기화하지 않음
        if (FirebaseApp.getApps().isEmpty()) {
            String credentialsBase64 = firebaseProperties.credentialsBase64();
            
            if (!StringUtils.hasText(credentialsBase64)) {
                log.warn("Firebase credentials-base64가 설정되지 않았습니다. FCM 기능이 동작하지 않습니다.");
                throw new IllegalStateException("Firebase credentials-base64가 설정되지 않았습니다.");
            }

            try {
                // Base64 디코딩
                byte[] decodedBytes = Base64.getDecoder().decode(credentialsBase64);
                ByteArrayInputStream credentialsStream = new ByteArrayInputStream(decodedBytes);

                // GoogleCredentials 생성
                GoogleCredentials credentials = GoogleCredentials.fromStream(credentialsStream);

                // FirebaseOptions 설정
                FirebaseOptions options = FirebaseOptions.builder()
                        .setCredentials(credentials)
                        .build();

                // FirebaseApp 초기화
                FirebaseApp app = FirebaseApp.initializeApp(options);
                log.info("Firebase Admin SDK 초기화 완료");
                return app;
            } catch (Exception e) {
                log.error("Firebase Admin SDK 초기화 실패", e);
                throw new RuntimeException("Firebase Admin SDK 초기화 실패", e);
            }
        } else {
            log.debug("FirebaseApp이 이미 초기화되어 있습니다.");
            return FirebaseApp.getInstance();
        }
    }

    @Bean
    public FirebaseMessaging firebaseMessaging(FirebaseApp firebaseApp) {
        return FirebaseMessaging.getInstance(firebaseApp);
    }
}

