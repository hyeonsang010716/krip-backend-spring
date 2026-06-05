package site.krip.domain.auth.dto.response;

import java.util.List;

/** 본인 제외 ACTIVE 유저 목록. */
public record OtherUserProfileListResponse(List<OtherUserProfileResponse> users) {
}
