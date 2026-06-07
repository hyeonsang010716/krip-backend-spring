package site.krip.domain.auth.dto.response;

import java.util.List;

/** 본인 제외 ACTIVE 유저 목록. */
// TODO 운영 전환 시 — 커서 페이지네이션 도입하면 next_cursor 필드 추가 필요.
public record OtherUserProfileListResponse(List<OtherUserProfileResponse> users) {
}
