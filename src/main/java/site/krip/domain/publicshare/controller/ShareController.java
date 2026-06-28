package site.krip.domain.publicshare.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import site.krip.domain.publicshare.dto.response.PublicPlanResponse;
import site.krip.domain.publicshare.service.SharePlanService;

/**
 * 공개 공유 endpoint — 경로 {@code /api/public/share}.
 *
 * <p>인증 불필요 — 세 인증 필터가 {@code /api/public} 을 화이트리스트로 제외한다.
 * 접근 제어는 오로지 공유 토큰 검증으로만 이뤄진다.
 */
@RestController
@RequestMapping("/api/public/share")
@RequiredArgsConstructor
public class ShareController {

    private final SharePlanService sharePlanService;

    /**
     * 공유 토큰으로 플랜 단건 조회 (공개).
     *
     * <ul>
     *   <li>토큰 무효/만료 → 400</li>
     *   <li>디코드 성공했으나 plan 이 사라짐 → 404</li>
     *   <li>응답에서 소유자 식별(user_id) 제외</li>
     * </ul>
     */
    @GetMapping("/plan/{share_token}")
    public PublicPlanResponse getSharedPlan(@PathVariable("share_token") String shareToken) {
        return sharePlanService.getPlanByToken(shareToken);
    }
}
