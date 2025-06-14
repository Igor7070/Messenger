package com.example.unl_pos12.config.messenger;

import com.example.unl_pos12.service.OpenAIService;
import okhttp3.WebSocket;
import okio.ByteString;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AudioWebSocketHandler extends BinaryWebSocketHandler {
    private final OpenAIService openAIService;
    private final Map<String, WebSocket> openAiSessions = new ConcurrentHashMap<>();

    public AudioWebSocketHandler(OpenAIService openAIService) { // MODIFIED: Убрано SimpMessagingTemplate
        this.openAIService = openAIService;
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
        String roomId = extractRoomId(session);
        if (roomId == null) {
            session.close(CloseStatus.BAD_DATA.withReason("Room ID missing"));
            return;
        }

        String sessionId = session.getId();
        byte[] audioData = message.getPayload().array();
        WebSocket openAiWebSocket = openAiSessions.computeIfAbsent(roomId,
                k -> openAIService.createOpenAIWebSocket(roomId, sessionId)); // MODIFIED: Убрано messagingTemplate
        if (openAiWebSocket != null) {
            openAiWebSocket.send(ByteString.of(audioData));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String roomId = extractRoomId(session);
        if (roomId != null) {
            WebSocket openAiWebSocket = openAiSessions.remove(roomId);
            if (openAiWebSocket != null) {
                openAiWebSocket.close(1000, "Session closed");
            }
        }
        super.afterConnectionClosed(session, status);
    }

    private String extractRoomId(WebSocketSession session) {
        String uri = session.getUri().toString();
        return uri.contains("roomId=") ? uri.split("roomId=")[1] : null;
    }
}
