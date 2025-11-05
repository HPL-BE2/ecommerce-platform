package kr.hhplus.be.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.retry.annotation.EnableRetry;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

@SpringBootApplication
@EnableCaching
@EnableRetry
public class ServerApplication {

	private static final Logger log = LoggerFactory.getLogger(ServerApplication.class);
	private static final List<String> API_CATALOG = List.of(
			"GET    /api/v1/products",
			"GET    /api/v1/products/{productId}",
			"POST   /api/v1/orders",
			"PATCH  /api/v1/orders/{orderId}/complete",
			"POST   /api/v1/wallets/{userId}/topups"
	);

	public static void main(String[] args) {
		SpringApplication.run(ServerApplication.class, args);
	}

	@Bean
	CommandLineRunner logApiCatalogOnStartup() {
		return args -> {
			var today = LocalDate.now();
			var updateDate = today.format(DateTimeFormatter.ISO_DATE);
			var divider = "=".repeat(64);

			var builder = new StringBuilder()
					.append("\n")
					.append(divider).append("\n")
					.append("개발 완료 API 목록 (Updated: ").append(updateDate).append(")\n");

			API_CATALOG.forEach(api -> builder.append(" - ").append(api).append("\n"));
			builder.append(divider);

			log.info(builder.toString());
		};
	}

}
