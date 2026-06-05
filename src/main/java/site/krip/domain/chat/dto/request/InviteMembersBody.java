package site.krip.domain.chat.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 그룹 방 멤버 초대 요청. 친구만 허용. */
public record InviteMembersBody(
        @NotEmpty @Size(max = 50) List<String> userIds
) {
}
