package grit.stockIt;

import grit.stockIt.global.config.KisApiProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@EnableConfigurationProperties(KisApiProperties.class)
@SpringBootApplication
public class StockItApplication {

	public static void main(String[] args) {
		SpringApplication.run(StockItApplication.class, args);
	}

}
