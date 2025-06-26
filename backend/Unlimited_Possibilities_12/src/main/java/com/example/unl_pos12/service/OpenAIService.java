package com.example.unl_pos12.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.theokanning.openai.completion.chat.ChatCompletionRequest;
import com.theokanning.openai.completion.chat.ChatCompletionResult;
import com.theokanning.openai.completion.chat.ChatMessage;
import com.theokanning.openai.service.OpenAiService;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.*;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

@Service
public class OpenAIService {
    private static final Logger LOGGER = Logger.getLogger(OpenAIService.class.getName());
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper mapper = new ObjectMapper();
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build();
    private final ConcurrentHashMap<String, ByteArrayOutputStream> audioBuffers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastSentTimestamps = new ConcurrentHashMap<>();
    // NEW: Храним recipientId для каждой сессии
    private final ConcurrentHashMap<String, String> sessionToRecipientMap = new ConcurrentHashMap<>();

    @Value("${openai.api.key}")
    private String apiKey;

    public OpenAIService(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    // Оставляем generateCompletion без изменений...
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
        LOGGER.info("Handling audio message for roomId: " + roomId + ", sessionId: " + sessionId + ", data length: " + audioData.length);
        if (audioData == null || audioData.length == 0) {
            LOGGER.warning("Empty audio data for roomId: " + roomId);
            return;
        }
        try {
            String bufferKey = roomId + "_" + sessionId;
            ByteArrayOutputStream audioBuffer = audioBuffers.computeIfAbsent(bufferKey, k -> new ByteArrayOutputStream());
            // Ограничение размера буфера (1 МБ)
            if (audioBuffer.size() + audioData.length > 1_000_000) {
                LOGGER.warning("Audio buffer exceeded 1MB for roomId: " + roomId + ", resetting");
                audioBuffer.reset();
            }
            audioBuffer.write(audioData);
            File debugFile = new File("debug_audio_" + bufferKey + ".raw");
            try (FileOutputStream fos = new FileOutputStream(debugFile, true)) {
                fos.write(audioData);
            } catch (IOException e) {
                LOGGER.severe("Error writing debug audio for roomId " + roomId + ": " + e.getMessage());
            }
            long currentTime = System.currentTimeMillis();
            long lastSentTime = lastSentTimestamps.getOrDefault(bufferKey, 0L);
            if (currentTime - lastSentTime >= 5000 && audioBuffer.size() >= 40000) { // Уменьшено с 80000
                byte[] audioBytes = audioBuffer.toByteArray();
                audioBuffer.reset();
                lastSentTimestamps.put(bufferKey, currentTime);
                byte[] wavBytes = convertToWav(audioBytes);
                String transcription = transcribeAudio(wavBytes);
                if (transcription != null && !transcription.trim().isEmpty()) {
                    sendTranscription(roomId, sessionId, transcription);
                } else {
                    LOGGER.warning("Empty transcription for roomId: " + roomId);
                    sendError(roomId, sessionId, "Empty transcription received from Whisper API");
                }
            }
        } catch (Exception e) {
            LOGGER.severe("Error processing audio for roomId " + roomId + ": " + e.getMessage());
            sendError(roomId, sessionId, "Error processing audio: " + e.getMessage());
        }
    }

    private byte[] convertToWav(byte[] rawAudio) throws IOException {
        AudioFormat format = new AudioFormat(16000, 16, 1, true, false);
        ByteArrayInputStream bais = new ByteArrayInputStream(rawAudio);
        AudioInputStream ais = new AudioInputStream(bais, format, rawAudio.length / format.getFrameSize());
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            AudioSystem.write(ais, AudioFileFormat.Type.WAVE, baos);
        } catch (Exception e) {
            LOGGER.severe("Error converting to WAV: " + e.getMessage());
            throw new IOException("Failed to convert to WAV", e);
        } finally {
            ais.close();
        }
        return baos.toByteArray();
    }

    private String transcribeAudio(byte[] wavAudio) throws IOException {
        RequestBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", "audio.wav",
                        RequestBody.create(wavAudio, MediaType.parse("audio/wav")))
                .addFormDataPart("model", "whisper-1")
                .build();

        Request request = new Request.Builder()
                .url("https://api.openai.com/v1/audio/transcriptions")
                .header("Authorization", "Bearer " + apiKey)
                .post(body)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "No response body";
                LOGGER.severe("Whisper API error: " + response.code() + ", " + errorBody);
                throw new IOException("Whisper API error: " + response.code() + ", " + errorBody);
            }
            String responseBody = response.body().string();
            ObjectNode json = (ObjectNode) mapper.readTree(responseBody);
            return json.get("text").asText();
        }
    }

    private void sendTranscription(String roomId, String sessionId, String transcription) {
        ObjectNode transcriptionMessage = mapper.createObjectNode();
        transcriptionMessage.put("transcription", transcription);
        transcriptionMessage.put("sessionId", sessionId);
        try {
            String messageJson = mapper.writeValueAsString(transcriptionMessage);
            // Отправляем себе
            messagingTemplate.convertAndSend("/topic/transcription/" + roomId, messageJson);
            LOGGER.info("Sent transcription to /topic/transcription/" + roomId + ": " + transcription + ", sessionId: " + sessionId);
            // NEW: Отправляем собеседнику
            String recipientId = sessionToRecipientMap.getOrDefault(roomId + "_" + sessionId, null);
            if (recipientId != null) {
                messagingTemplate.convertAndSend("/topic/friend-transcription/" + roomId + "/" + recipientId, messageJson);
                LOGGER.info("Sent transcription to /topic/friend-transcription/" + roomId + "/" + recipientId + ": " + transcription);
            } else {
                LOGGER.warning("No recipientId found for roomId: " + roomId + ", sessionId: " + sessionId);
            }
        } catch (Exception e) {
            LOGGER.severe("Error sending transcription for roomId " + roomId + ": " + e.getMessage());
        }
    }

    private void sendError(String roomId, String sessionId, String error) {
        ObjectNode errorMessage = mapper.createObjectNode();
        errorMessage.put("error", error);
        errorMessage.put("sessionId", sessionId);
        try {
            messagingTemplate.convertAndSend("/topic/transcription/" + roomId, mapper.writeValueAsString(errorMessage));
            LOGGER.info("Sent error to /topic/transcription/" + roomId + ": " + error);
        } catch (Exception e) {
            LOGGER.severe("Error sending error message for roomId " + roomId + ": " + e.getMessage());
        }
    }

    // NEW: Метод для регистрации recipientId...
    public void registerRecipient(String roomId, String sessionId, String recipientId) {
        sessionToRecipientMap.put(roomId + "_" + sessionId, recipientId);
        LOGGER.info("Registered recipientId: " + recipientId + " for roomId: " + roomId + ", sessionId: " + sessionId);
    }
}
