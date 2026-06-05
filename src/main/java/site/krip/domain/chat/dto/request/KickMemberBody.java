package site.krip.domain.chat.dto.request;

import jakarta.validation.constraints.NotBlank;

/** 그룹 방 강퇴 요청. 요청자는 creator 여야 함. */
public record KickMemberBody(
        @NotBlank String userId
) {
}
