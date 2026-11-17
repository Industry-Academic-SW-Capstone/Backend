package grit.launch.extra;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

// 부가 서비스만 띄운다. 클래스패스에는 이 모듈과 common·contract 만 있어 grit.stockIt 을 훑어도 다른 서비스의 빈은 없다.
// grit.stockIt 밖에 두는 이유: app 의 StockItApplication 이 grit.stockIt 전체를 훑으므로, 그 아래 있으면 실행 클래스가 둘이 된다.
// 엔티티·리포지토리도 grit.stockIt 에서 찾게 자동 설정 패키지를 더한다.
@SpringBootApplication(scanBasePackages = "grit.stockIt", exclude = UserDetailsServiceAutoConfiguration.class)
@AutoConfigurationPackage(basePackages = "grit.stockIt")
public class ExtraApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(ExtraApplication.class);
        application.setAdditionalProfiles("extra");
        application.run(args);
    }
}
