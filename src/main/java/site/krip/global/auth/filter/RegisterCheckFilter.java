package site.krip.global.auth.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.Nullable;
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
import site.krip.global.common.exception.ApiException;
import site.krip.global.common.exception.ErrorResponse;

import java.io.IOException;
import java.util.Optional;

/**
 * 2차 회원가입 완료 + 활성 상태 검증.
 *
 * <pre>
 *   유저 없음            → 401
 *   status == INACTIVE   → 419 (탈퇴 유예, 커스텀 코드)
 *   status == SUSPENDED  → 403 (정지)
 *   2차 회원가입 미완료    → 403
 *   정상                 → REGISTERED 캐싱 후 통과
 * </pre>
 *
 * 판정 결과를 (양성·음성 모두) 캐싱해 캐시 히트 시 DB 조회를 생략한다 — 미가입(403)·탈퇴유예(419)
 * 유저도 캐싱되어 요청마다 DB 를 때리지 않는다. 캐시는 상태 전이 시 무효화된다.
 */
public class RegisterCheckFilter extends OncePerRequestFilter {

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

        // 1. 캐시 히트(양성·음성 모두) → DB 없이 판정
        RegisteredCacheManager.Outcome cached = cache.lookup(userId);
        if (cached != null) {
            if (!reject(cached, response)) {
                chain.doFilter(request, response);
            }
            return;
        }

        // 2. 캐시 미스 → DB 조회 (유저 + detail 한 번에). 유저 없음(401)은 캐싱하지 않는다.
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

        // 3. 판정 결과 캐싱 후 처리
        RegisteredCacheManager.Outcome outcome = classify(found.get());
        cache.cache(userId, outcome);
        if (!reject(outcome, response)) {
            chain.doFilter(request, response);
        }
    }

    /** INACTIVE/SUSPENDED 는 상태로 차단, detail 없음 → UNREGISTERED, 그 외 → REGISTERED. */
    private static RegisteredCacheManager.Outcome classify(User user) {
        if (user.getStatus() == UserStatus.INACTIVE) {
            return RegisteredCacheManager.Outcome.INACTIVE;
        }
        if (user.getStatus() == UserStatus.SUSPENDED) {
            return RegisteredCacheManager.Outcome.SUSPENDED;
        }
        if (user.getDetail() == null) {
            return RegisteredCacheManager.Outcome.UNREGISTERED;
        }
        return RegisteredCacheManager.Outcome.REGISTERED;
    }

    /** outcome 이 거부 대상이면 에러를 쓰고 true, REGISTERED 면 false(통과). */
    private boolean reject(RegisteredCacheManager.Outcome outcome, HttpServletResponse response) throws IOException {
        switch (outcome) {
            case INACTIVE -> FilterSupport.writeError(response, mapper, ApiException.WITHDRAWAL_PENDING_STATUS,
                    ErrorResponse.of(
                            "회원 탈퇴가 진행 중입니다. 30일 유예 기간 종료 후 영구 삭제됩니다.",
                            ApiException.WITHDRAWAL_PENDING_FIELD));
            case SUSPENDED -> FilterSupport.writeError(response, mapper, 403,
                    ErrorResponse.of("정지된 계정입니다."));
            case UNREGISTERED -> FilterSupport.writeError(response, mapper, 403,
                    ErrorResponse.of("2차 회원가입이 필요합니다."));
            case REGISTERED -> {
                return false;
            }
        }
        return true;
    }

    private @Nullable String authenticatedUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return null;
        }
        return auth.getName();
    }
}
