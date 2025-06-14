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

    public WebSocket createOpenAIWebSocket(String roomId, String sessionId) {
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
                    String configJson = mapper.writeValueAsString(config);
                    System.out.println("Sending OpenAI config: " + configJson);
                    webSocket.send(configJson);
                } catch (Exception e) {
                    System.err.println("Error sending OpenAI config for roomId " + roomId + ": " + e.getMessage());
                }
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                System.out.println("Received OpenAI message for roomId " + roomId + ": " + text);
                try {
                    ObjectNode json = (ObjectNode) mapper.readTree(text);
                    String messageType = json.get("type").asText();
                    System.out.println("Message type: " + messageType);
                    if ("response.audio_transcript.delta".equals(messageType)) {
                        String transcription = json.get("delta").asText();
                        System.out.println("Transcription delta for roomId " + roomId + ": " + transcription);
                        ObjectNode transcriptionMessage = mapper.createObjectNode();
                        transcriptionMessage.put("transcription", transcription);
                        transcriptionMessage.put("sessionId", sessionId);
                        // Попробуем отправить без перевода пока
                        String messageJson = mapper.writeValueAsString(transcriptionMessage);
                        System.out.println("Sending to STOMP topic /topic/transcription/" + roomId + ": " + messageJson);
                        messagingTemplate.convertAndSend("/topic/transcription/" + roomId, messageJson);
                    }
                } catch (Exception e) {
                    System.err.println("Error parsing OpenAI message for roomId " + roomId + ": " + e.getMessage());
                }
            }

            @Override
            public void onMessage(WebSocket webSocket, ByteString bytes) {
                System.out.println("Received binary message from OpenAI for roomId " + roomId + ": " + bytes.size() + " bytes");
            }

            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                System.out.println("OpenAI WebSocket closing for roomId " + roomId + ": " + reason);
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                System.err.println("OpenAI WebSocket failure for roomId " + roomId + ": " + t.getMessage());
                ObjectNode errorMessage = mapper.createObjectNode();
                errorMessage.put("error", "Transcription service failed: " + t.getMessage());
                try {
                    messagingTemplate.convertAndSend("/topic/transcription/" + roomId, mapper.writeValueAsString(errorMessage));
                } catch (Exception e) {
                    System.err.println("Error sending error message to STOMP for roomId " + roomId + ": " + e.getMessage());
                }
            }
        };

        System.out.println("Creating OpenAI WebSocket for roomId: " + roomId);
        return client.newWebSocket(request, listener);
    }
}
