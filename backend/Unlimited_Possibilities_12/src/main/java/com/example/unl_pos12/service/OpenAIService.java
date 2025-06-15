package com.example.unl_pos12.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.theokanning.openai.completion.chat.ChatCompletionRequest;
import com.theokanning.openai.completion.chat.ChatCompletionResult;
import com.theokanning.openai.completion.chat.ChatMessage;
import com.theokanning.openai.service.OpenAiService;
import okhttp3.*;
import okio.ByteString;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Base64;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

@Service
public class OpenAIService {
    @Value("${openai.api.key}")
    private String apiKey;
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
    private final SimpMessagingTemplate messagingTemplate; // ADDED
    private final Map<String, WebSocket> openAiSessions = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private static final Logger LOGGER = Logger.getLogger(OpenAIService.class.getName());

    public OpenAIService(SimpMessagingTemplate messagingTemplate) { // ADDED: Конструктор
        this.messagingTemplate = messagingTemplate;
    }

    public String generateCompletion(String prompt) {
        OpenAiService service = new OpenAiService(apiKey, DEFAULT_TIMEOUT);
        ChatCompletionRequest chatCompletionRequest = ChatCompletionRequest.builder()
                .model("gpt-4o")
                .messages(Collections.singletonList(
                        new ChatMessage("user", prompt)
                ))
                .maxTokens(3200)
                .temperature(0.9)
                .build();
        ChatCompletionResult chatCompletionResult = service.createChatCompletion(chatCompletionRequest);
        String response = chatCompletionResult.getChoices().get(0).getMessage().getContent();
        return response;
    }

    public void handleAudioMessage(String roomId, String sessionId, byte[] audioData) {
        LOGGER.info("Handling audio message for roomId: " + roomId + ", data length: " + audioData.length);
        if (audioData == null || audioData.length == 0) {
            LOGGER.warning("Empty audio data for roomId: " + roomId);
            return;
        }
        WebSocket openAiWebSocket = openAiSessions.computeIfAbsent(roomId, k -> createOpenAIWebSocket(roomId, sessionId));
        if (openAiWebSocket != null) {
            try {
                // Формируем сообщение input_audio_buffer.append
                ObjectNode audioMessage = mapper.createObjectNode();
                audioMessage.put("type", "input_audio_buffer.append");
                audioMessage.put("audio", Base64.getEncoder().encodeToString(audioData));
                String messageJson = mapper.writeValueAsString(audioMessage);
                openAiWebSocket.send(messageJson);
                LOGGER.info("Sent input_audio_buffer.append to OpenAI WebSocket for roomId: " + roomId + ", audio length: " + audioData.length);
            } catch (Exception e) {
                LOGGER.severe("Error sending audio to OpenAI WebSocket for roomId " + roomId + ": " + e.getMessage());
            }
        } else {
            LOGGER.severe("Failed to create OpenAI WebSocket for roomId: " + roomId);
        }
    }

    public void closeWebSocket(String roomId) {
        WebSocket webSocket = openAiSessions.remove(roomId);
        if (webSocket != null) {
            webSocket.close(1000, "Call ended");
            LOGGER.info("Closed OpenAI WebSocket for roomId: " + roomId);
        }
    }

    private WebSocket createOpenAIWebSocket(String roomId, String sessionId) {
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();

        Request request = new Request.Builder()
                .url("wss://api.openai.com/v1/realtime?model=gpt-4o-realtime-preview")
                .header("Authorization", "Bearer " + apiKey)
                .header("OpenAI-Beta", "realtime=v1")
                .build();

        WebSocketListener listener = new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                LOGGER.info("OpenAI WebSocket opened for roomId: " + roomId);
                ObjectNode config = mapper.createObjectNode();
                config.put("type", "session.update");
                ObjectNode sessionConfig = mapper.createObjectNode();
                sessionConfig.putArray("modalities").add("text").add("audio");
                sessionConfig.put("instructions", "You are a friendly assistant.");
                sessionConfig.put("input_audio_format", "pcm16");
                sessionConfig.put("output_audio_format", "pcm16");
                ObjectNode transcriptionConfig = mapper.createObjectNode();
                transcriptionConfig.put("model", "whisper-1");
                sessionConfig.set("input_audio_transcription", transcriptionConfig);
                config.set("session", sessionConfig);
                try {
                    webSocket.send(mapper.writeValueAsString(config));
                    LOGGER.info("Sent session.update config to OpenAI for roomId: " + roomId);
                } catch (Exception e) {
                    LOGGER.severe("Error sending OpenAI config for roomId " + roomId + ": " + e.getMessage());
                }
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                try {
                    LOGGER.info("Received OpenAI message for roomId " + roomId + ": " + text);
                    ObjectNode json = (ObjectNode) mapper.readTree(text);
                    String messageType = json.get("type").asText();
                    if ("response.audio_transcript.delta".equals(messageType)) {
                        String transcription = json.get("delta").asText();
                        ObjectNode transcriptionMessage = mapper.createObjectNode();
                        transcriptionMessage.put("transcription", transcription);
                        transcriptionMessage.put("sessionId", sessionId);
                        String messageJson = mapper.writeValueAsString(transcriptionMessage);
                        messagingTemplate.convertAndSend("/topic/transcription/" + roomId, messageJson);
                        LOGGER.info("Sent transcription to /topic/transcription/" + roomId + ": " + transcription);
                    } else if ("error".equals(messageType)) {
                        LOGGER.severe("OpenAI error for roomId " + roomId + ": " + json.get("error").toString());
                    } else if ("session.created".equals(messageType)) {
                        LOGGER.info("Session created for roomId " + roomId + ": " + json.get("session").get("id").asText());
                    } else if ("session.updated".equals(messageType)) {
                        LOGGER.info("Session updated for roomId " + roomId);
                    }
                } catch (Exception e) {
                    LOGGER.severe("Error parsing OpenAI message for roomId " + roomId + ": " + e.getMessage());
                }
            }

            @Override
            public void onMessage(WebSocket webSocket, ByteString bytes) {
                LOGGER.info("Received binary message from OpenAI for roomId " + roomId + ", length: " + bytes.size());
            }

            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                openAiSessions.remove(roomId);
                LOGGER.info("OpenAI WebSocket closing for roomId " + roomId + ": " + reason);
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                openAiSessions.remove(roomId);
                LOGGER.severe("OpenAI WebSocket failure for roomId " + roomId + ": " + t.getMessage());
                ObjectNode errorMessage = mapper.createObjectNode();
                errorMessage.put("error", "Transcription service failed: " + t.getMessage());
                try {
                    messagingTemplate.convertAndSend("/topic/transcription/" + roomId, mapper.writeValueAsString(errorMessage));
                } catch (Exception e) {
                    LOGGER.severe("Error sending error message to STOMP for roomId " + roomId + ": " + e.getMessage());
                }
            }
        };

        WebSocket webSocket = client.newWebSocket(request, listener);
        LOGGER.info("Initiated OpenAI WebSocket creation for roomId: " + roomId);
        return webSocket;
    }
}
