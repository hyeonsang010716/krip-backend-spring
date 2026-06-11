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
import site.krip.global.auth.RequestAttributes;
import site.krip.global.auth.jwt.JwtProvider;
import site.krip.global.auth.jwt.TokenRevocationService;
import site.krip.global.common.exception.ErrorResponse;

import java.io.IOException;
import java.util.List;

/**
 * 유저 로그인 JWT 검증 → {@link SecurityContextHolder} 에 인증 주입.
 *
 * <p>토큰 소스: {@code X-Auth-Token} 헤더(앱) → {@code utk} 쿠키(웹) 순.
 * 폐기(로그아웃)된 jti 는 거부하고, jti·만료를 request 에 심어 로그아웃이 폐기에 쓰게 한다.
 * 토큰 부재 시엔 인증을 비워 두고, 401 응답은 {@code AuthenticationEntryPoint} 가 낸다.
 */
public class LoginAuthFilter extends OncePerRequestFilter {

    private final JwtProvider jwtProvider;
    private final TokenRevocationService revocation;
    private final String cookieName;
    private final ObjectMapper mapper;
    private final RequestMatcher skip;

    public LoginAuthFilter(JwtProvider jwtProvider, TokenRevocationService revocation,
                           String cookieName, ObjectMapper mapper, RequestMatcher skip) {
        this.jwtProvider = jwtProvider;
        this.revocation = revocation;
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
                // 빈 쿠키는 "토큰 없음"으로 취급 — 헤더 경로와 동일하게 blank 스킵.
                if (cookieName.equals(c.getName()) && c.getValue() != null && !c.getValue().isBlank()) {
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

        JwtProvider.ParsedToken parsed;
        try {
            parsed = jwtProvider.parse(token);
        } catch (ExpiredJwtException e) {
            FilterSupport.writeError(response, mapper, 401, ErrorResponse.of("토큰이 만료되었습니다."));
            return;
        } catch (JwtException | IllegalArgumentException e) {
            FilterSupport.writeError(response, mapper, 401, ErrorResponse.of("유효하지 않은 토큰입니다."));
            return;
        }

        if (parsed.userId() == null) {
            FilterSupport.writeError(response, mapper, 401, ErrorResponse.of("유효하지 않은 토큰입니다."));
            return;
        }
        if (revocation.isRevoked(parsed.jti())) {
            FilterSupport.writeError(response, mapper, 401, ErrorResponse.of("로그아웃된 토큰입니다."));
            return;
        }

        // 로그아웃 핸들러가 폐기에 쓰도록 jti·만료를 심는다.
        request.setAttribute(RequestAttributes.JWT_JTI, parsed.jti());
        request.setAttribute(RequestAttributes.JWT_EXP, parsed.expiresAt());

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(parsed.userId(), null, List.of()));
        chain.doFilter(request, response);
    }
}
