package site.krip.domain.chat.repository;

/** 유저 활성 방의 last-read seq 투영 (미설정 시 seq 는 null). */
public record LastReadSeq(String roomId, Long seq) {
}
