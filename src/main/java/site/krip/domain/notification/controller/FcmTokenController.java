package site.krip.domain.notification.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import site.krip.domain.notification.dto.request.RegisterFcmTokenBody;
import site.krip.domain.notification.dto.request.UnregisterFcmTokenBody;
import site.krip.domain.notification.dto.response.FcmTokenResponse;
import site.krip.domain.notification.service.FcmService;
import site.krip.global.auth.CurrentUserId;
import site.krip.global.common.dto.MessageResponse;

/**
 * FCM 토큰 관리. 경로: {@code /api/notification/fcm-token}.
 */
@RestController
@RequestMapping("/api/notification/fcm-token")
@RequiredArgsConstructor
public class FcmTokenController {

    private final FcmService fcmService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FcmTokenResponse register(@CurrentUserId String userId, @Valid @RequestBody RegisterFcmTokenBody body) {
        return fcmService.registerToken(userId, body.token());
    }

    @DeleteMapping
    public MessageResponse unregister(@CurrentUserId String userId, @Valid @RequestBody UnregisterFcmTokenBody body) {
        fcmService.unregisterToken(userId, body.token());
        return new MessageResponse("FCM 토큰을 해제했습니다.");
    }
}
