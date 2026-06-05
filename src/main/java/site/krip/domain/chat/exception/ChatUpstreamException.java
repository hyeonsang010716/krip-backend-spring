package site.krip.domain.chat.exception;

import site.krip.global.common.exception.ApiException;

/**
 * 외부 저장소(Mongo seq insert 등)가 재시도 상한 내 수렴 실패 — 500.
 * 이 시점엔 dedupe 키가 이미 풀려 같은 client_msg_id 로 재시도 가능.
 */
public class ChatUpstreamException extends ApiException {

    public ChatUpstreamException(String message) {
        super(500, message);
    }
}
