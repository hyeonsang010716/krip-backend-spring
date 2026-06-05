package site.krip.domain.tour.dto.response;

import java.time.Instant;

/**
 * 플랜 공유 토큰 응답.
 * 공개 조회 URL: {@code /api/public/share/plan/{share_token}}.
 */
public record ShareTokenResponse(
        String shareToken,
        Instant expiresAt
) {
}
