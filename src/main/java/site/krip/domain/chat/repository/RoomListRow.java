package site.krip.domain.chat.repository;

import site.krip.domain.chat.entity.ChatRoom;

/** 방 목록 행 투영 — room + 1:1 peer(그룹은 null) + 알림 차단 여부. */
public record RoomListRow(ChatRoom room, String peerUserId, Boolean notificationMuted) {
}
