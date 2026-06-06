package site.krip.global.auth.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import site.krip.global.common.exception.ErrorResponse;

import java.io.IOException;

/**
 * 인증 필터 공통 — {@code {"detail": ...}} 에러 바디 작성.
 */
final class FilterSupport {

    private FilterSupport() {
    }

    static void writeError(HttpServletResponse response, ObjectMapper mapper,
                           int status, ErrorResponse body) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        mapper.writeValue(response.getWriter(), body);
    }
}
