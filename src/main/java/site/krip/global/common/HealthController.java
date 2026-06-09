package site.krip.global.common;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 단순 liveness 핑 — 의존성 미포함(프로세스/HTTP 응답성만). 인증 필터 제외 경로라 토큰 없이 접근 가능.
 *
 * <p>의존성을 보는 readiness(무중단 배포 게이팅 + DB/Redis 장애 감지)는 Actuator 의
 * {@code /actuator/health/readiness}, 의존성 없는 liveness 는 {@code /actuator/health/liveness} 사용.
 */
@RestController
public class HealthController {

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }
}
