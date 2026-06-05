package site.krip.global.common;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 인증 필터의 제외 경로라 토큰 없이 접근 가능.
 */
@RestController
public class HealthController {

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    @GetMapping("/ready")
    public Map<String, String> ready() {
        return Map.of("status", "ready");
    }
}
