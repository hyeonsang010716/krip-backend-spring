package site.krip.domain.chat.dto.response;

import java.util.List;

/** 참여자 / 초대 가능 친구 목록. */
public record RoomMemberListResponse(List<RoomMemberResponse> items) {
}
