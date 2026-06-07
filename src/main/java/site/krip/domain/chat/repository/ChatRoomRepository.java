package site.krip.domain.chat.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import site.krip.domain.chat.entity.ChatRoom;
import site.krip.domain.chat.entity.ChatRoomType;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 채팅방 RDB 접근.
 *
 * <p>{@code last_message_*} 갱신은 bulk UPDATE(엔티티 인스턴스를 expire 시키지 않음).
 * 송신·reconcile 모두 동시 갱신 regress 방지를 위해
 * {@code updateLastMessageIfGreater}(WHERE seq IS NULL OR &lt; new) 사용.
 */
public interface ChatRoomRepository extends JpaRepository<ChatRoom, String> {

    /** 정식 커서 페이지네이션 도입 전 폭주 방어 상한. */
    int PAGE_SIZE = 500;

    /** canonical 정렬된 DIRECT 방 조회 — 호출측이 a&lt;b 로 정렬해 넘긴다. */
    @Query("select r from ChatRoom r where r.type = site.krip.domain.chat.entity.ChatRoomType.DIRECT "
            + "and r.directUserAId = :a and r.directUserBId = :b")
    Optional<ChatRoom> findDirectByPair(@Param("a") String userAId, @Param("b") String userBId);

    /**
     * 유저의 활성 방 (effective_last_at DESC, chat_room_id DESC). 1:1 peer 는 CASE 로 함께 파생, 그룹은 null.
     * chat_room_id tie-breaker 로 동률 시각의 순서를 결정화한다(커서 도입 시 skip/중복 방지).
     */
    @Query("select new site.krip.domain.chat.repository.RoomListRow(r, "
            + "(case when r.type = site.krip.domain.chat.entity.ChatRoomType.DIRECT then "
            + "  (case when r.directUserAId = :uid then r.directUserBId else r.directUserAId end) "
            + " else null end), "
            + "m.notificationMuted) "
            + "from ChatRoom r, ChatRoomMember m "
            + "where m.chatRoomId = r.chatRoomId and m.userId = :uid and m.left = false "
            + "order by r.effectiveLastAt desc, r.chatRoomId desc")
    List<RoomListRow> findRoomsOfUser(@Param("uid") String userId, Pageable pageable);

    /**
     * last_message 역정규화 — 송신 핫패스/reconcile 배치 공용. 호출부가 비-트랜잭션이라 자체 tx 로 수렴.
     * 동시 갱신 시 낮은 seq 가 덮어쓰지 않게 {@code seq IS NULL OR < new} 가드.
     */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("update ChatRoom r set r.lastMessageId = :messageId, r.lastMessageServerSeq = :seq, "
            + "r.lastMessageAt = :at where r.chatRoomId = :roomId "
            + "and (r.lastMessageServerSeq is null or r.lastMessageServerSeq < :seq)")
    void updateLastMessageIfGreater(@Param("roomId") String roomId, @Param("messageId") String messageId,
                                    @Param("seq") long serverSeq, @Param("at") Instant at);
}
