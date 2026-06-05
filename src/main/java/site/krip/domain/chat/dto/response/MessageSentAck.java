package site.krip.domain.chat.dto.response;

import java.time.Instant;

/** 메시지 송신 ACK — 발신 세션에 직송(fan-out 미경유). */
public record MessageSentAck(
        String clientMsgId,
        String messageId,
        long serverSeq,
        Instant createdAt
) {
}
