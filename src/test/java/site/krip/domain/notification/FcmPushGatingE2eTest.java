package site.krip.domain.notification;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import site.krip.domain.chat.service.RoomService;
import site.krip.domain.notification.fcm.FcmClient;
import site.krip.domain.notification.repository.FcmTokenRepository;
import site.krip.domain.notification.service.FcmService;
import site.krip.domain.notification.service.MuteService;
import site.krip.support.IntegrationTestSupport;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FCM 채팅 푸시 게이팅 E2E — 실 DB + mock {@link FcmClient} 로 sendChatPush 게이팅
 * 캐스케이드(방 뮤트 → 전역 뮤트 → 토큰 → multicast → 만료 정리)를 검증한다.
 */
class FcmPushGatingE2eTest extends IntegrationTestSupport {

    @MockitoBean
    private FcmClient fcmClient;

    @Autowired
    private FcmService fcmService;

    @Autowired
    private FcmTokenRepository tokenRepo;

    @Autowired
    private MuteService muteService;

    @Autowired
    private RoomService roomService;

    @BeforeEach
    void enableFcm() {
        when(fcmClient.isEnabled()).thenReturn(true);
        when(fcmClient.sendMulticast(anyList(), anyString(), anyString(), anyMap()))
                .thenReturn(new FcmClient.SendResult(1, List.of()));
    }

    private record Group(String owner, String b, String c, String room) {
        String tokenB() {
            return "tok-" + b;
        }

        String tokenC() {
            return "tok-" + c;
        }
    }

    /** owner + b + c 그룹방 + b/c FCM 토큰 등록. */
    private Group setupGroupWithTokens(String label) throws Exception {
        String owner = fixtures.createActiveUser(label + "owner");
        String b = fixtures.createActiveUser(label + "B");
        String c = fixtures.createActiveUser(label + "C");
        befriendViaApi(owner, b);
        befriendViaApi(owner, c);
        String room = roomService.createGroupRoom(owner, label + "방", List.of(b, c)).chatRoomId();
        Group g = new Group(owner, b, c, room);
        fcmService.registerToken(b, g.tokenB());
        fcmService.registerToken(c, g.tokenC());
        return g;
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<List<String>> tokenCaptor() {
        return ArgumentCaptor.forClass(List.class);
    }

    @Test
    @DisplayName("방 뮤트된 유저는 push 대상에서 제외된다")
    void roomMutedUserExcluded() throws Exception {
        Group g = setupGroupWithTokens("rm");
        muteService.setRoomMute(g.b(), g.room(), true);

        fcmService.sendChatPush(List.of(g.b(), g.c()), g.room(), g.owner(), "본문", "제목");

        ArgumentCaptor<List<String>> cap = tokenCaptor();
        verify(fcmClient).sendMulticast(cap.capture(), anyString(), anyString(), anyMap());
        assertThat(cap.getValue()).containsExactly(g.tokenC());
    }

    @Test
    @DisplayName("전역 뮤트된 유저는 push 대상에서 제외된다")
    void globalMutedUserExcluded() throws Exception {
        Group g = setupGroupWithTokens("gm");
        muteService.setGlobalMute(g.c(), true);

        fcmService.sendChatPush(List.of(g.b(), g.c()), g.room(), g.owner(), "본문", "제목");

        ArgumentCaptor<List<String>> cap = tokenCaptor();
        verify(fcmClient).sendMulticast(cap.capture(), anyString(), anyString(), anyMap());
        assertThat(cap.getValue()).containsExactly(g.tokenB());
    }

    @Test
    @DisplayName("토큰이 없으면 multicast 를 호출하지 않고 0 반환")
    void noTokensSkipsMulticast() throws Exception {
        String owner = fixtures.createActiveUser("ntOwner");
        String d = fixtures.createActiveUser("ntD");
        String e = fixtures.createActiveUser("ntE");
        befriendViaApi(owner, d);
        befriendViaApi(owner, e);
        String room = roomService.createGroupRoom(owner, "무토큰방", List.of(d, e)).chatRoomId();

        int sent = fcmService.sendChatPush(List.of(d, e), room, owner, "본문", "제목");

        assertThat(sent).isZero();
        verify(fcmClient, never()).sendMulticast(anyList(), anyString(), anyString(), anyMap());
    }

    @Test
    @DisplayName("만료(UNREGISTERED) 토큰은 발송 후 삭제된다")
    void invalidTokensDeleted() throws Exception {
        Group g = setupGroupWithTokens("inv");
        when(fcmClient.sendMulticast(anyList(), anyString(), anyString(), anyMap()))
                .thenReturn(new FcmClient.SendResult(1, List.of(g.tokenC())));

        fcmService.sendChatPush(List.of(g.b(), g.c()), g.room(), g.owner(), "본문", "제목");

        assertThat(tokenRepo.findByToken(g.tokenC())).isEmpty();
        assertThat(tokenRepo.findByToken(g.tokenB())).isPresent();
    }

    @Test
    @DisplayName("발송 중 재등록된 토큰은 무효 정리에서 제외된다(race 가드)")
    void reRegisteredTokenSpared() throws Exception {
        Group g = setupGroupWithTokens("race");
        when(fcmClient.sendMulticast(anyList(), anyString(), anyString(), anyMap()))
                .thenAnswer(inv -> {
                    fcmService.registerToken(g.c(), g.tokenC()); // 발송 중 동일 토큰 재등록(updated_at 갱신)
                    return new FcmClient.SendResult(1, List.of(g.tokenC()));
                });

        fcmService.sendChatPush(List.of(g.b(), g.c()), g.room(), g.owner(), "본문", "제목");

        assertThat(tokenRepo.findByToken(g.tokenC())).isPresent();
    }

    @Test
    @DisplayName("FCM 비활성 모드면 게이팅 통과해도 multicast 없이 0 반환")
    void disabledModeReturnsZero() throws Exception {
        Group g = setupGroupWithTokens("dis");
        when(fcmClient.isEnabled()).thenReturn(false);

        int sent = fcmService.sendChatPush(List.of(g.b(), g.c()), g.room(), g.owner(), "본문", "제목");

        assertThat(sent).isZero();
        verify(fcmClient, never()).sendMulticast(anyList(), anyString(), anyString(), anyMap());
    }
}
