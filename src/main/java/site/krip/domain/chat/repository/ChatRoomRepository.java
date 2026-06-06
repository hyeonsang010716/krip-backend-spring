package site.krip.domain.chat.repository;

import jakarta.transaction.Transactional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import site.krip.domain.chat.entity.ChatRoom;
import site.krip.domain.chat.entity.ChatRoomType;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 채팅방 RDB 접근.
 *
 * <p>{@code last_message_*} 갱신은 bulk UPDATE(엔티티 인스턴스를 expire 시키지 않음).
 * reconcile 은 regress 방지를 위해 {@code updateLastMessageIfGreater}(WHERE seq IS NULL OR &lt; new) 사용.
 */
public interface ChatRoomRepository extends JpaRepository<ChatRoom, String> {

    /** 정식 커서 페이지네이션 도입 전 폭주 방어 상한. */
    int PAGE_SIZE = 500;

    /** canonical 정렬된 DIRECT 방 조회 — 호출측이 a&lt;b 로 정렬해 넘긴다. */
    @Query("select r from ChatRoom r where r.type = site.krip.domain.chat.entity.ChatRoomType.DIRECT "
            + "and r.directUserAId = :a and r.directUserBId = :b")
    Optional<ChatRoom> findDirectByPair(@Param("a") String userAId, @Param("b") String userBId);

    /**
     * 유저의 활성 방 (effective_last_at DESC). 1:1 peer 는 CASE 로 함께 파생, 그룹은 null.
     */
    @Query("select new site.krip.domain.chat.repository.RoomListRow(r, "
            + "(case when r.type = site.krip.domain.chat.entity.ChatRoomType.DIRECT then "
            + "  (case when r.directUserAId = :uid then r.directUserBId else r.directUserAId end) "
            + " else null end), "
            + "m.notificationMuted) "
            + "from ChatRoom r, ChatRoomMember m "
            + "where m.chatRoomId = r.chatRoomId and m.userId = :uid and m.left = false "
            + "order by r.effectiveLastAt desc")
    List<RoomListRow> findRoomsOfUser(@Param("uid") String userId, Pageable pageable);

    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("update ChatRoom r set r.lastMessageId = :messageId, r.lastMessageServerSeq = :seq, "
            + "r.lastMessageAt = :at where r.chatRoomId = :roomId")
    void updateLastMessage(@Param("roomId") String roomId, @Param("messageId") String messageId,
                           @Param("seq") long serverSeq, @Param("at") Instant at);

    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("update ChatRoom r set r.lastMessageId = :messageId, r.lastMessageServerSeq = :seq, "
            + "r.lastMessageAt = :at where r.chatRoomId = :roomId "
            + "and (r.lastMessageServerSeq is null or r.lastMessageServerSeq < :seq)")
    void updateLastMessageIfGreater(@Param("roomId") String roomId, @Param("messageId") String messageId,
                                    @Param("seq") long serverSeq, @Param("at") Instant at);
}
