package site.krip.domain.chat.repository;

import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import site.krip.domain.auth.entity.User;
import site.krip.domain.chat.entity.ChatRoomMember;
import site.krip.domain.chat.entity.ChatRoomMemberId;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 채팅방 멤버 RDB 접근.
 *
 * <p>읽음 마킹은 GREATEST(COALESCE(기존,0), new) 네이티브 UPDATE 로 regress 를 DB 레벨 차단.
 */
public interface ChatRoomMemberRepository extends JpaRepository<ChatRoomMember, ChatRoomMemberId> {

    /** 방의 활성 멤버 User + detail (joined_at ASC) — 참여자 목록 노출용. */
    @Query("select u from ChatRoomMember m join User u on u.userId = m.userId "
            + "left join fetch u.detail "
            + "where m.chatRoomId = :roomId and m.left = false "
            + "order by m.joinedAt asc, u.userId asc")
    List<User> findActiveMemberUsers(@Param("roomId") String chatRoomId);

    /** 방의 활성 멤버 user_id — room:members 캐시 miss 시 일괄 로드. */
    @Query("select m.userId from ChatRoomMember m where m.chatRoomId = :roomId and m.left = false")
    List<String> findActiveMemberIds(@Param("roomId") String chatRoomId);

    @Query("select case when count(m) > 0 then true else false end from ChatRoomMember m "
            + "where m.chatRoomId = :roomId and m.userId = :userId and m.left = false")
    boolean isActiveMember(@Param("roomId") String chatRoomId, @Param("userId") String userId);

    /** 유저의 활성 방 ID — WS 연결 시 초기 구독용. */
    @Query("select m.chatRoomId from ChatRoomMember m where m.userId = :userId and m.left = false")
    List<String> findUserRoomIds(@Param("userId") String userId);

    /** 방에서 푸시 받을 수 있는 id — 활성 멤버 + 방 알림 미차단 (FCM 푸시 게이팅). */
    @Query("select m.userId from ChatRoomMember m where m.chatRoomId = :roomId "
            + "and m.userId in :userIds and m.left = false and m.notificationMuted is not true")
    List<String> findPushableUserIdsInRoom(@Param("roomId") String chatRoomId,
                                           @Param("userIds") java.util.Collection<String> userIds);

    /** 유저 전체 활성 방의 last_read seq (NULL 포함 — 서비스가 0 으로 정규화). */
    @Query("select m.chatRoomId, m.lastReadMessageServerSeq from ChatRoomMember m "
            + "where m.userId = :userId and m.left = false")
    List<Object[]> findLastReadSeqsAll(@Param("userId") String userId);

    @Query("select m.chatRoomId, m.lastReadMessageServerSeq from ChatRoomMember m "
            + "where m.userId = :userId and m.left = false and m.chatRoomId in :roomIds")
    List<Object[]> findLastReadSeqsIn(@Param("userId") String userId,
                                      @Param("roomIds") Collection<String> roomIds);

    /**
     * 읽음 마킹 — last_read = GREATEST(COALESCE(기존,0), :seq) + last_read_at=now. 활성 멤버만.
     * @return 영향받은 row 수(0 이면 탈퇴자/미존재 → 서비스가 403).
     */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query(value = "UPDATE chat_room_member "
            + "SET last_read_message_server_seq = GREATEST(COALESCE(last_read_message_server_seq, 0), :seq), "
            + "    last_read_at = now() "
            + "WHERE chat_room_id = :roomId AND user_id = :userId AND is_left = false",
            nativeQuery = true)
    int markRead(@Param("roomId") String chatRoomId, @Param("userId") String userId,
                 @Param("seq") long newSeq);

    @Query("select m.lastReadMessageServerSeq from ChatRoomMember m "
            + "where m.chatRoomId = :roomId and m.userId = :userId")
    Optional<Long> findLastReadSeq(@Param("roomId") String chatRoomId, @Param("userId") String userId);
}
