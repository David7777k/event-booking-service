package io.github.david7777k.seatflow.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {

    private static final String BEARER = "bearerAuth";

    @Bean
    OpenAPI seatflowOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("SeatFlow API")
                        .version("1.0.0")
                        .description("""
                                Event seat booking.

                                Browsing events and venues needs no account. Booking requires a
                                token from `POST /api/v1/auth/login`; managing the catalogue
                                requires the ADMIN role.

                                A booking holds its seats for ten minutes. Confirming accepts an
                                `Idempotency-Key` header so a retry after a timeout neither fails
                                nor creates a second booking.

                                Errors follow RFC 9457: a `title`, a `detail`, and for validation
                                failures an `errors` map keyed by field.
                                """)
                        .license(new License().name("MIT").url("https://opensource.org/licenses/MIT")))

                // Declared once and applied globally so every protected operation
                // shows the padlock, rather than annotating each controller.
                .components(new Components().addSecuritySchemes(BEARER,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Paste the accessToken returned by register or login")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }
}
