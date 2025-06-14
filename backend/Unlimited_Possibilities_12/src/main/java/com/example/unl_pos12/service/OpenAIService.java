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
import java.util.Collections;

@Service
public class OpenAIService {
    @Value("${openai.api.key}")
    private String apiKey;
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
    private final SimpMessagingTemplate messagingTemplate; // ADDED

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

    public WebSocket createOpenAIWebSocket(String roomId, String sessionId) { // MODIFIED: Убрано messagingTemplate как параметр
        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder()
                .url("wss://api.openai.com/v1/realtime?model=gpt-4o-realtime-preview")
                .header("Authorization", "Bearer " + apiKey)
                .header("OpenAI-Beta", "realtime=v1")
                .build();

        ObjectMapper mapper = new ObjectMapper();

        WebSocketListener listener = new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                System.out.println("Connected to OpenAI Realtime API for roomId: " + roomId);
                ObjectNode config = mapper.createObjectNode();
                config.put("type", "session.update");
                ObjectNode sessionConfig = mapper.createObjectNode();
                sessionConfig.putArray("modalities").add("text").add("audio");
                sessionConfig.put("instructions", "Transcribe the audio in real-time and return the text.");
                sessionConfig.put("voice", "alloy");
                sessionConfig.putNull("turn_detection");
                sessionConfig.put("input_audio_format", "pcm16");
                sessionConfig.put("output_audio_format", "pcm16");
                config.set("session", sessionConfig);
                try {
                    webSocket.send(mapper.writeValueAsString(config));
                } catch (Exception e) {
                    System.err.println("Error sending OpenAI config: " + e.getMessage());
                }
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                try {
                    ObjectNode json = (ObjectNode) mapper.readTree(text);
                    if ("response.audio_transcript.delta".equals(json.get("type").asText())) {
                        String transcription = json.get("delta").asText();
                        System.out.println("Transcription delta for roomId " + roomId + ": " + transcription);
                        ObjectNode transcriptionMessage = mapper.createObjectNode();
                        transcriptionMessage.put("transcription", transcription);
                        transcriptionMessage.put("sessionId", sessionId);
                        messagingTemplate.convertAndSend("/topic/transcription/" + roomId,
                                mapper.writeValueAsString(transcriptionMessage));
                    }
                } catch (Exception e) {
                    System.err.println("Error parsing OpenAI message: " + e.getMessage());
                }
            }

            @Override
            public void onMessage(WebSocket webSocket, ByteString bytes) {
                // Игнорируем входящие аудиоданные от OpenAI
            }

            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                System.out.println("OpenAI WebSocket closing for roomId " + roomId + ": " + reason);
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                System.err.println("OpenAI WebSocket failure for roomId " + roomId + ": " + t.getMessage());
            }
        };

        return client.newWebSocket(request, listener);
    }
}
