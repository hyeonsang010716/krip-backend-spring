package site.krip.domain.friend.dto.request;

import jakarta.validation.constraints.NotBlank;

/** 친구 요청 보내기 요청. */
public record SendFriendRequestBody(@NotBlank String addresseeId) {
}
