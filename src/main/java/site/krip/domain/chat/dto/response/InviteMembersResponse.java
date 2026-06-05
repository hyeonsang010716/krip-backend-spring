package site.krip.domain.chat.dto.response;

import java.util.List;

/** 멤버 초대 응답 — 실제 초대된 id + 이미 멤버라 skip 된 id. */
public record InviteMembersResponse(
        List<String> invitedUserIds,
        List<String> skippedAlreadyMember
) {
}
