package site.krip.global.auth.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import site.krip.global.auth.jwt.JwtProvider;
import site.krip.global.common.exception.ErrorResponse;

import java.io.IOException;
import java.util.List;

/**
 * 유저 로그인 JWT 검증 → {@link SecurityContextHolder} 에 인증 주입.
 *
 * <p>토큰 소스: {@code X-Auth-Token} 헤더(앱) → {@code utk} 쿠키(웹) 순.
 * 토큰 부재 시엔 인증을 비워 두고, 401 응답은 {@code AuthenticationEntryPoint} 가 낸다.
 */
public class LoginAuthFilter extends OncePerRequestFilter {

    private final JwtProvider jwtProvider;
    private final String cookieName;
    private final ObjectMapper mapper;
    private final RequestMatcher skip;

    public LoginAuthFilter(JwtProvider jwtProvider, String cookieName, ObjectMapper mapper,
                           RequestMatcher skip) {
        this.jwtProvider = jwtProvider;
        this.cookieName = cookieName;
        this.mapper = mapper;
        this.skip = skip;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return skip.matches(request);
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
        String token = extractToken(request);
        if (token == null) {
            chain.doFilter(request, response);
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

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null, List.of()));
        chain.doFilter(request, response);
    }
}
