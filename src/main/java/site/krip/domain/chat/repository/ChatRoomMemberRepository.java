package site.krip.domain.chat.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import site.krip.domain.auth.entity.User;
import site.krip.domain.chat.entity.ChatRoomMember;
import site.krip.domain.chat.entity.ChatRoomMemberId;

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
    @Query("select new site.krip.domain.chat.repository.LastReadSeq(m.chatRoomId, m.lastReadMessageServerSeq) "
            + "from ChatRoomMember m where m.userId = :userId and m.left = false")
    List<LastReadSeq> findLastReadSeqsAll(@Param("userId") String userId);

    /**
     * 읽음 마킹 — last_read = GREATEST(COALESCE(기존,0), :seq) + last_read_at=now. 활성 멤버만.
     * @return 영향받은 row 수(0 이면 탈퇴자/미존재 → 서비스가 403).
     */
    @Modifying(clearAutomatically = true)
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

    /**
     * 퇴장/강퇴 soft delete — 활성 멤버일 때만 is_left=true.
     * @return 영향 row 수(1=이번 호출이 실제로 전이, 0=이미 탈퇴/미존재 → 중복 호출).
     */
    @Modifying(clearAutomatically = true)
    @Query(value = "UPDATE chat_room_member SET is_left = true "
            + "WHERE chat_room_id = :roomId AND user_id = :userId AND is_left = false",
            nativeQuery = true)
    int markLeftIfActive(@Param("roomId") String chatRoomId, @Param("userId") String userId);

    /**
     * 재초대 — 탈퇴 멤버만 재입장(is_left=false) + joined_at 갱신 + mute 리셋(last_read 유지).
     * 루프 내 신규 insert 와 섞여 호출되므로 flush 후 실행해 보류 중인 insert 유실을 막는다.
     * @return 영향 row 수(1=이번 호출이 실제로 재입장, 0=활성/미존재 → 경합으로 먼저 처리됨).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE chat_room_member "
            + "SET is_left = false, joined_at = now(), notification_muted = NULL "
            + "WHERE chat_room_id = :roomId AND user_id = :userId AND is_left = true",
            nativeQuery = true)
    int rejoinIfLeft(@Param("roomId") String chatRoomId, @Param("userId") String userId);
}
