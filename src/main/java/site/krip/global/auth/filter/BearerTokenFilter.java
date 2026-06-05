package site.krip.global.auth.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import site.krip.global.common.exception.ErrorResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;


public class BearerTokenFilter extends OncePerRequestFilter {

    private static final List<String> EXCLUDE_PREFIXES;

    static {
        List<String> p = new ArrayList<>(List.of("/api/auth/login", "/api/public", "/api/ws"));
        p.addAll(FilterSupport.DOC_EXCLUDE_PREFIXES);
        EXCLUDE_PREFIXES = List.copyOf(p);
    }

    private final byte[] accessTokenBytes;
    private final ObjectMapper mapper;

    public BearerTokenFilter(String accessToken, ObjectMapper mapper) {
        this.accessTokenBytes = accessToken.getBytes(StandardCharsets.UTF_8);
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (FilterSupport.isExcluded(request.getRequestURI(),
                FilterSupport.COMMON_EXCLUDE_PATHS, EXCLUDE_PREFIXES)) {
            chain.doFilter(request, response);
            return;
        }

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
