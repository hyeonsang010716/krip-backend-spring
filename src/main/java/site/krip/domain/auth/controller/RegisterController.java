package site.krip.domain.auth.controller;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import site.krip.domain.auth.dto.request.RegisterRequest;
import site.krip.domain.auth.dto.response.RegisterResponse;
import site.krip.domain.auth.service.RegisterService;
import site.krip.global.auth.CurrentUserId;
import site.krip.global.cache.RegisteredCacheManager;

/** 2차 회원가입. */
@RestController
@RequestMapping("/api/auth/register")
public class RegisterController {

    private final RegisterService registerService;
    private final RegisteredCacheManager registeredCache;

    public RegisterController(RegisterService registerService, RegisteredCacheManager registeredCache) {
        this.registerService = registerService;
        this.registeredCache = registeredCache;
    }

    @PostMapping
    public ResponseEntity<RegisterResponse> register(@CurrentUserId String userId,
                                                     @Valid @RequestBody RegisterRequest request) {
        registerService.registerDetail(userId, request);

        // 2차 회원가입 완료 → REGISTERED 캐시 세팅
        registeredCache.setFlag(userId);

        return ResponseEntity.ok(new RegisterResponse("회원가입이 완료되었습니다."));
    }
}
