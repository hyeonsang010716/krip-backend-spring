package site.krip.global.auth.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;
import site.krip.global.auth.RequestAttributes;
import site.krip.global.support.MdcTaskDecorator;

import java.io.IOException;
import java.util.UUID;

/**
 * 요청 추적 ID 부여.
 * 헤더로 들어온 X-Request-ID 가 있으면 재사용, 없으면 생성. 응답 헤더와 MDC 에 노출.
 */
public class RequestIdFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-Request-ID";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String requestId = request.getHeader(HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        request.setAttribute(RequestAttributes.REQUEST_ID, requestId);
        response.setHeader(HEADER, requestId);
        MDC.put(MdcTaskDecorator.REQUEST_ID, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MdcTaskDecorator.REQUEST_ID);
        }
    }
}
