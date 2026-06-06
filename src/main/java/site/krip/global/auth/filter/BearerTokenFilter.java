package site.krip.global.auth.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import site.krip.global.common.exception.ErrorResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 글로벌 정적 액세스 토큰(게이트웨이 공유 비밀) 검증.
 */
public class BearerTokenFilter extends OncePerRequestFilter {

    private final byte[] accessTokenBytes;
    private final ObjectMapper mapper;
    private final RequestMatcher skip;

    public BearerTokenFilter(String accessToken, ObjectMapper mapper, RequestMatcher skip) {
        this.accessTokenBytes = accessToken.getBytes(StandardCharsets.UTF_8);
        this.mapper = mapper;
        this.skip = skip;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return skip.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization == null) {
            FilterSupport.writeError(response, mapper, 401,
                    ErrorResponse.of("Authorization 헤더가 필요합니다"));
            return;
        }

        String[] parts = authorization.split(" ", 2);
        if (parts.length != 2 || !parts[0].equalsIgnoreCase("bearer")) {
            FilterSupport.writeError(response, mapper, 401,
                    ErrorResponse.of("Bearer 토큰 형식이 올바르지 않습니다"));
            return;
        }

        byte[] provided = parts[1].getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(provided, accessTokenBytes)) {
            FilterSupport.writeError(response, mapper, 401,
                    ErrorResponse.of("유효하지 않은 토큰입니다"));
            return;
        }

        chain.doFilter(request, response);
    }
}
