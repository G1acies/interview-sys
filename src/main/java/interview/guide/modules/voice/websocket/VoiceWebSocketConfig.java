package interview.guide.modules.voice.websocket;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 配置类。
 * <p>
 * 将 {@link VoiceInterviewWebSocketHandler} 注册到路径 {@code /ws/voice-interview/{sessionId}}，
 * 允许前端通过该路径建立语音面试的实时 WebSocket 连接。
 * <p>
 * 路径格式：
 * <ul>
 *   <li><b>开发环境</b> — {@code ws://localhost:8080/ws/voice-interview/123}</li>
 *   <li><b>生产环境</b> — {@code wss://example.com/ws/voice-interview/123}</li>
 * </ul>
 * sessionId 从 URL 路径中提取，对应 {@link VoiceInterviewSessionEntity} 的主键 id。
 * <p>
 * 注册细节：
 * <ul>
 *   <li>{@code setAllowedOrigins("*")} — 允许所有来源（开发阶段），生产环境应限定具体域名</li>
 *   <li>Spring 自动将 SockJS 回退添加到路径末尾（{@code withSockJS()}），
 *       但语音面试需要原生 WebSocket，无需 SockJS 回退</li>
 * </ul>
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class VoiceWebSocketConfig implements WebSocketConfigurer {

    private final VoiceInterviewWebSocketHandler voiceInterviewWebSocketHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(voiceInterviewWebSocketHandler, "/ws/voice-interview/{sessionId}")
                .setAllowedOrigins("*");
    }
}
