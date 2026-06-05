package site.krip.domain.chat.dto.request;

import jakarta.validation.constraints.NotBlank;

/** 1:1 방 생성 요청. */
public record CreateDirectRoomBody(
        @NotBlank String peerUserId
) {
}
