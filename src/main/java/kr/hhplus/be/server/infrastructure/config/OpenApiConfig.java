package kr.hhplus.be.server.infrastructure.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI commerceOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("HHPlus E-commerce API")
                        .description("API documentation for the HHPlus commerce backend.")
                        .version("v1"))
                .addServersItem(new Server()
                        .url("http://localhost:8080")
                        .description("Local"));
    }
}
