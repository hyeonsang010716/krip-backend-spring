package site.krip.domain.chat.exception;

import site.krip.global.common.exception.ApiException;

/** 요청한 chat_room 이 없거나 삭제됨 — 404. */
public class ChatRoomNotFoundException extends ApiException {

    public ChatRoomNotFoundException(String message) {
        super(404, message);
    }
}
