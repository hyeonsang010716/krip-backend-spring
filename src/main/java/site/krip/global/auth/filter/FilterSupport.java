package site.krip.global.auth.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import site.krip.global.common.exception.ErrorResponse;

import java.io.IOException;
import java.util.List;

/**
 * 인증 필터 공통 유틸 — 경로 제외 판정 + {@code {"detail": ...}} 에러 바디 작성.
 */
final class FilterSupport {

    /** 모든 인증 필터가 공통으로 건너뛰는 정확 매칭 경로 (health/probe/docs). */
    static final List<String> COMMON_EXCLUDE_PATHS = List.of(
            "/health", "/ready",
            "/docs", "/openapi.json"
    );

    /** springdoc/swagger 정적 리소스 + 스펙 prefix — 모든 필터에서 제외. */
    static final List<String> DOC_EXCLUDE_PREFIXES = List.of(
            "/swagger-ui", "/openapi.json"
    );

    private FilterSupport() {
    }

    /**
     * 제외 판정 — 정확 경로는 완전 일치, prefix 는 세그먼트 경계(같거나 {@code prefix + "/"} 로 시작)만 인정.
     * 단순 {@code startsWith} 는 {@code /api/public} 이 {@code /api/publicxxx} 까지 삼켜 인증을 우회시키므로 쓰지 않는다.
     */
    static boolean isExcluded(String path, List<String> exactPaths, List<String> prefixes) {
        if (exactPaths.contains(path)) {
            return true;
        }
        for (String prefix : prefixes) {
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
    }

    static void writeError(HttpServletResponse response, ObjectMapper mapper,
                           int status, ErrorResponse body) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        mapper.writeValue(response.getWriter(), body);
    }
}
