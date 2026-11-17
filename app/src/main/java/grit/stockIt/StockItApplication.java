package grit.stockIt;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

// 네 서비스를 한 프로세스로 띄운다(로컬 개발, 서비스를 넘나드는 흐름 테스트). 서비스별 실행은 각 모듈의 grit.launch.*.
// 로그인은 서비스가 직접 확인하고 요청 인증은 JWT 필터가 토큰만으로 한다. 쓰지 않는 기본 메모리 사용자를 만들지 않는다.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class StockItApplication {

    public static void main(String[] args) {
        SpringApplication.run(StockItApplication.class, args);
    }
}
