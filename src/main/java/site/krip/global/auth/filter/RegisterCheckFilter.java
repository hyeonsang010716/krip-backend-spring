package site.krip.global.auth.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import site.krip.domain.auth.entity.User;
import site.krip.domain.auth.entity.UserStatus;
import site.krip.domain.auth.repository.UserRepository;
import site.krip.global.cache.RegisteredCacheManager;
import site.krip.global.common.exception.ErrorResponse;

import java.io.IOException;
import java.util.Optional;

/**
 * 2차 회원가입 완료 + 활성 상태 검증.
 *
 * <pre>
 *   유저 없음            → 401
 *   status == INACTIVE   → 419 (탈퇴 유예, 커스텀 코드)
 *   2차 회원가입 미완료    → 403
 *   정상                 → REGISTERED 플래그 캐싱 후 통과
 * </pre>
 *
 * Redis 캐시 히트 시 DB 조회를 생략한다. 양성 결과만 캐싱하며, 탈퇴 요청 시 무효화된다.
 */
public class RegisterCheckFilter extends OncePerRequestFilter {

    private static final int WITHDRAWAL_PENDING_STATUS_CODE = 419;
    private static final Logger log = LoggerFactory.getLogger(RegisterCheckFilter.class);

    private final UserRepository userRepository;
    private final RegisteredCacheManager cache;
    private final ObjectMapper mapper;
    private final RequestMatcher skip;

    public RegisterCheckFilter(UserRepository userRepository, RegisteredCacheManager cache,
                               ObjectMapper mapper, RequestMatcher skip) {
        this.userRepository = userRepository;
        this.cache = cache;
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
        String userId = authenticatedUserId();
        if (userId == null) {
            chain.doFilter(request, response);
            return;
        }

        // 1. 캐시 히트 → 통과
        if (cache.exists(userId)) {
            chain.doFilter(request, response);
            return;
        }

        // 2. DB 조회 — 유저 + detail (2차 가입 여부) 를 한 번에
        Optional<User> found;
        try {
            found = userRepository.findByIdWithDetail(userId);
        } catch (Exception e) {
            log.error("회원가입 상태 확인 DB 조회 실패 (user_id={})", userId, e);
            FilterSupport.writeError(response, mapper, 500,
                    ErrorResponse.of("회원가입 상태 확인 중 오류가 발생했습니다."));
            return;
        }

        if (found.isEmpty()) {
            FilterSupport.writeError(response, mapper, 401, ErrorResponse.of("존재하지 않는 유저입니다."));
            return;
        }

        User user = found.get();

        if (user.getStatus() == UserStatus.INACTIVE) {
            FilterSupport.writeError(response, mapper, WITHDRAWAL_PENDING_STATUS_CODE,
                    ErrorResponse.of(
                            "회원 탈퇴가 진행 중입니다. 30일 유예 기간 종료 후 영구 삭제됩니다.",
                            "withdrawal_pending"));
            return;
        }

        if (user.getDetail() == null) {
            FilterSupport.writeError(response, mapper, 403, ErrorResponse.of("2차 회원가입이 필요합니다."));
            return;
        }

        // 3. 양성 결과 캐싱 후 통과
        cache.setFlag(userId);
        chain.doFilter(request, response);
    }

    private String authenticatedUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return null;
        }
        return auth.getName();
    }
}
