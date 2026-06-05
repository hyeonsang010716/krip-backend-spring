package site.krip.domain.chat.controller;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import site.krip.domain.chat.dto.request.EditMessageBody;
import site.krip.domain.chat.dto.response.EditMessageResponse;
import site.krip.domain.chat.service.MessageService;
import site.krip.global.auth.CurrentUserId;

/**
 * 메시지 편집/삭제 REST — {@code /api/chat/messages}.
 * REST 에는 WS session_id 가 없어 fan-out 자기 에코 skip 대상 없음(빈 문자열).
 */
@RestController
@RequestMapping("/api/chat/messages")
public class ChatMessageController {

    private final MessageService messageService;

    public ChatMessageController(MessageService messageService) {
        this.messageService = messageService;
    }

    @PatchMapping("/{message_id}")
    public EditMessageResponse edit(@CurrentUserId String userId, @PathVariable("message_id") String messageId,
                                    @Valid @RequestBody EditMessageBody body) {
        return messageService.editMessage(messageId, userId, "", body.content());
    }

    @DeleteMapping("/{message_id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@CurrentUserId String userId, @PathVariable("message_id") String messageId) {
        messageService.deleteMessage(messageId, userId, "");
    }
}
