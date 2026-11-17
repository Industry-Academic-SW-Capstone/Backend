package grit.stockIt.global.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@Configuration
@ConditionalOnProperty(name = "stockit.db.enabled", havingValue = "true", matchIfMissing = true)
@EnableJpaAuditing
public class JpaConfig {
}