package site.krip.global.config;

import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * CORS 허용 origin.
 *
 * {@code allowedOrigins} 는 HTTP CORS 전용.
 * {@code appAllowedOrigins} 는 네이티브 앱(Capacitor) WebSocket 핸드셰이크에서만 추가로 허용하는 origin 으로, HTTP CORS 에는 포함하지 않는다.
 */
@Validated
@ConfigurationProperties(prefix = "krip.cors")
public record CorsProperties(
        @NotEmpty List<String> allowedOrigins,
        @NotEmpty List<String> appAllowedOrigins
) {
}
