package site.krip.domain.chat.ws;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 채팅 WebSocket 등록 — {@code /api/ws/chat}.
 *
 * <p>Origin/Auth 는 {@link ChatHandshakeInterceptor} 가 핸드셰이크에서 검증한다.
 * 서브프로토콜 {@code krip.chat.v1} echo 는 핸들러가 {@code SubProtocolCapable} 로 선언해 협상한다.
 * 인증 필터(Bearer/Login/RegisterCheck)는 WS 업그레이드 경로를 직접 검증하지 않으므로 인터셉터에 위임.
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class ChatWebSocketConfig implements WebSocketConfigurer {

    private final ChatWebSocketHandler handler;
    private final ChatHandshakeInterceptor handshakeInterceptor;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/api/ws/chat")
                .addInterceptors(handshakeInterceptor)
                // Origin 검증은 인터셉터가 수행하므로 컨테이너 단 origin 체크는 전체 허용으로 위임.
                .setAllowedOriginPatterns("*");
    }
}
