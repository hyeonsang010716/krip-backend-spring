package site.krip.domain.friend.dto.request;

import jakarta.validation.constraints.NotBlank;

/** 유저 차단 요청. */
public record BlockUserBody(@NotBlank String targetUserId) {
}
