package grit.stockIt.global.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

// 서비스마다 자기 schema 의 표만 보게 한다. 커넥션을 열 때 search_path 를 정해, 이름만 쓴 SQL(아웃박스·처리 기록·잠금 조회)도
// 자기 schema 로 간다. 비어 있으면 서버 기본값(public) — 테스트와 V20 이전의 한 프로세스 실행.
// 서비스별 프로필이 자기 schema 를, V20 이후 app 하나로 띄울 때는 DB_SEARCH_PATH=broker,exchange,extra 를 준다.
@Configuration
public class SearchPathConfig {

    private static final Pattern SCHEMA_NAME = Pattern.compile("[a-z_][a-z0-9_]*");

    @Bean
    public static BeanPostProcessor searchPathInitializer(@Value("${stockit.db.search-path:}") String searchPath) {
        List<String> schemas = Arrays.stream(searchPath.split(","))
                .map(String::trim)
                .filter(schema -> !schema.isEmpty())
                .toList();
        schemas.forEach(schema -> {
            if (!SCHEMA_NAME.matcher(schema).matches()) {
                throw new IllegalStateException("stockit.db.search-path 에 쓸 수 없는 schema 이름입니다: " + schema);
            }
        });
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String beanName) {
                if (bean instanceof HikariDataSource dataSource && !schemas.isEmpty()) {
                    dataSource.setConnectionInitSql("SET search_path TO " + String.join(", ", schemas));
                }
                return bean;
            }
        };
    }
}
