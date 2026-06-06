package site.krip.global.auth.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import site.krip.global.auth.RequestAttributes;
import site.krip.global.auth.jwt.JwtProvider;
import site.krip.global.common.exception.ErrorResponse;

import java.io.IOException;
import java.util.List;

/**
 * 유저 로그인 JWT 검증 → request 에 user_id 주입.
 *
 * <p>토큰 소스: {@code X-Auth-Token} 헤더(앱) → {@code utk} 쿠키(웹) 순.
 */
public class LoginAuthFilter extends OncePerRequestFilter {

    private static final List<String> EXCLUDE_PREFIXES =
            List.of("/api/auth/login", "/api/public", "/api/ws");

    private final JwtProvider jwtProvider;
    private final String cookieName;
    private final ObjectMapper mapper;

    public LoginAuthFilter(JwtProvider jwtProvider, String cookieName, ObjectMapper mapper) {
        this.jwtProvider = jwtProvider;
        this.cookieName = cookieName;
        this.mapper = mapper;
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader("X-Auth-Token");
        if (header != null && !header.isBlank()) {
            return header;
        }
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if (cookieName.equals(c.getName())) {
                    return c.getValue();
                }
            }
        }
        return null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (FilterSupport.isExcluded(request.getRequestURI(),
                EXCLUDE_PREFIXES)) {
            chain.doFilter(request, response);
            return;
        }

        String token = extractToken(request);
        if (token == null) {
            FilterSupport.writeError(response, mapper, 401, ErrorResponse.of("로그인이 필요합니다."));
            return;
        }

        String userId;
        try {
            userId = jwtProvider.parseUserId(token);
        } catch (ExpiredJwtException e) {
            FilterSupport.writeError(response, mapper, 401, ErrorResponse.of("토큰이 만료되었습니다."));
            return;
        } catch (JwtException | IllegalArgumentException e) {
            FilterSupport.writeError(response, mapper, 401, ErrorResponse.of("유효하지 않은 토큰입니다."));
            return;
        }

        if (userId == null) {
            FilterSupport.writeError(response, mapper, 401, ErrorResponse.of("유효하지 않은 토큰입니다."));
            return;
        }

        request.setAttribute(RequestAttributes.USER_ID, userId);
        chain.doFilter(request, response);
    }
}
