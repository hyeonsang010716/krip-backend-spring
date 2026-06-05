package site.krip.domain.notification.dto.response;

/** 미읽음 카운트 응답. 최대 999 로 캡. */
public record UnreadCountResponse(int unreadCount) {
}
