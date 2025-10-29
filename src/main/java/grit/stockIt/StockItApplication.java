package grit.stockIt;

import grit.stockIt.global.config.KisApiProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableConfigurationProperties(KisApiProperties.class)
@EnableScheduling
@EnableJpaAuditing
@SpringBootApplication
public class StockItApplication {

	public static void main(String[] args) {
		SpringApplication.run(StockItApplication.class, args);
	}

}
