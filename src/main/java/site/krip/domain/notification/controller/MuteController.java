package site.krip.domain.notification.controller;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import site.krip.domain.notification.dto.request.MuteToggleBody;
import site.krip.domain.notification.service.MuteService;
import site.krip.global.auth.CurrentUserId;
import site.krip.global.common.dto.MessageResponse;

/**
 * 알림 차단. 경로: {@code /api/notification/mute}.
 */
@RestController
@RequestMapping("/api/notification/mute")
public class MuteController {

    private final MuteService muteService;

    public MuteController(MuteService muteService) {
        this.muteService = muteService;
    }

    @PutMapping("/global")
    public MessageResponse setGlobalMute(@CurrentUserId String userId, @Valid @RequestBody MuteToggleBody body) {
        muteService.setGlobalMute(userId, body.muted());
        return new MessageResponse(body.muted() ? "모든 알림을 차단했습니다." : "알림 차단을 해제했습니다.");
    }

    @PutMapping("/rooms/{chat_room_id}")
    public MessageResponse setRoomMute(@CurrentUserId String userId, @PathVariable("chat_room_id") String chatRoomId,
                                       @Valid @RequestBody MuteToggleBody body) {
        muteService.setRoomMute(userId, chatRoomId, body.muted());
        return new MessageResponse(body.muted() ? "이 방의 알림을 차단했습니다." : "이 방의 알림 차단을 해제했습니다.");
    }
}
