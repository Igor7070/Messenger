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
    private final ConcurrentHashMap<String, String> sessionToRecipientMap = new ConcurrentHashMap<>(); // NEW: Храним recipientId для каждой сессии
    private final ConcurrentHashMap<String, String> userSettings = new ConcurrentHashMap<>(); // [ДОБАВЛЕНО] Хранилище настроек

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
            // NEW: Извлекаем recipientId из JSON или используем sessionId как ключ
            String bufferKey = roomId + "_" + sessionId;
            ByteArrayOutputStream audioBuffer = audioBuffers.computeIfAbsent(bufferKey, k -> new ByteArrayOutputStream());
            audioBuffer.write(audioData);
            File debugFile = new File("debug_audio_" + bufferKey + ".raw");
            try (FileOutputStream fos = new FileOutputStream(debugFile, true)) {
                fos.write(audioData);
            } catch (IOException e) {
                LOGGER.severe("Error writing debug audio for roomId " + roomId + ": " + e.getMessage());
            }
            long currentTime = System.currentTimeMillis();
            long lastSentTime = lastSentTimestamps.getOrDefault(bufferKey, 0L);
            if (currentTime - lastSentTime >= 5000 && audioBuffer.size() >= 80000) {
                byte[] audioBytes = audioBuffer.toByteArray();
                audioBuffer.reset();
                lastSentTimestamps.put(bufferKey, currentTime);
                byte[] wavBytes = convertToWav(audioBytes);
                String transcription = transcribeAudio(wavBytes);
                // [ДОБАВЛЕНО] Фильтрация транскрипции
                if (isValidTranscription(transcription)) {
                    sendTranscription(roomId, sessionId, transcription);
                } else {
                    LOGGER.warning("Filtered out invalid transcription for roomId: " + roomId + ": " + transcription);
                    //sendError(roomId, sessionId, "Invalid transcription filtered: " + transcription);
                }
            }
        } catch (Exception e) {
            LOGGER.severe("Error processing audio for roomId " + roomId + ": " + e.getMessage());
            //sendError(roomId, sessionId, "Error processing audio: " + e.getMessage());
        }
    }

    // [ДОБАВЛЕНО] Метод для фильтрации транскрипции
    private boolean isValidTranscription(String transcription) {
        if (transcription == null || transcription.trim().isEmpty()) {
            LOGGER.info("Filtered out null or empty transcription: " + transcription);
            return false; // Пустой текст
        }
        // Проверка на минимальную длину (например, < 3 символов)
        if (transcription.trim().length() < 3) {
            LOGGER.info("Filtered out short transcription: " + transcription);
            return false;
        }
        return true; // Транскрипция считается разговорной речью
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
                // [ДОБАВЛЕНО] Вызываем TTS, если включён
                sendTTS(roomId, sessionId, recipientId, transcription);
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

    // [ДОБАВЛЕНО] Метод для синтеза речи
    private byte[] synthesizeSpeech(String text) throws IOException {
        LOGGER.info("Synthesizing speech for text: " + text);
        if (text == null || text.trim().isEmpty()) {
            LOGGER.warning("Invalid input for TTS: text=" + text);
            throw new IOException("Invalid text for TTS");
        }

        ObjectNode ttsRequest = mapper.createObjectNode();
        ttsRequest.put("model", "gpt-4o-mini-tts"); // Модель, указанная тобой
        ttsRequest.put("input", text);
        ttsRequest.put("voice", "nova"); // Пример голоса (можно заменить: echo, fable, onyx, nova, shimmer)

        RequestBody body = RequestBody.create(
                mapper.writeValueAsString(ttsRequest),
                MediaType.parse("application/json")
        );

        Request request = new Request.Builder()
                .url("https://api.openai.com/v1/audio/speech")
                .header("Authorization", "Bearer " + apiKey)
                .post(body)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "No response body";
                LOGGER.severe("TTS API error: " + response.code() + ", " + errorBody);
                throw new IOException("TTS API error: " + response.code() + ", " + errorBody);
            }
            byte[] audioBytes = response.body().bytes();
            LOGGER.info("Synthesized audio length: " + audioBytes.length + " bytes");
            return audioBytes;
        }
    }

    // [ДОБАВЛЕНО] Метод для отправки синтезированного аудио
    private void sendTTS(String roomId, String sessionId, String recipientId, String transcription) {
        System.out.println("Method sendTTS is working...");
        try {
            String translationEnabledKey = "translation_enabled_" + roomId + "_" + recipientId;
            String targetLanguageKey = "translation_language_" + roomId + "_" + recipientId;
            String ttsEnabledKey = "tts_enabled_" + roomId + "_" + recipientId;
            boolean isTranslationEnabled = Boolean.parseBoolean(userSettings.getOrDefault(translationEnabledKey, "false"));
            String targetLanguage = userSettings.getOrDefault(targetLanguageKey, "auto");
            boolean isTtsEnabled = Boolean.parseBoolean(userSettings.getOrDefault(ttsEnabledKey, "false"));

            if (isTranslationEnabled && !targetLanguage.equals("auto") && isTtsEnabled) {
                // Переводим транскрипцию
                String translatePrompt = String.format(
                        "Translate the following text to %s and return only the translated phrase in double quotes: \"%s\"",
                        targetLanguage, transcription
                );
                String translatedText = generateCompletion(translatePrompt);
                if (translatedText != null && translatedText.startsWith("\"") && translatedText.endsWith("\"")) {
                    translatedText = translatedText.substring(1, translatedText.length() - 1).trim();
                    if (!translatedText.isEmpty()) {
                        // Синтезируем аудио
                        byte[] ttsAudio = synthesizeSpeech(translatedText);
                        String ttsAudioBase64 = java.util.Base64.getEncoder().encodeToString(ttsAudio);
                        ObjectNode ttsMessage = mapper.createObjectNode();
                        ttsMessage.put("audio", ttsAudioBase64);
                        ttsMessage.put("sessionId", sessionId);
                        messagingTemplate.convertAndSend(
                                "/topic/tts/" + roomId + "/" + recipientId,
                                mapper.writeValueAsString(ttsMessage)
                        );
                        LOGGER.info("Sent TTS audio to /topic/tts/" + roomId + "/" + recipientId + ", length: " + ttsAudio.length + " bytes");
                    } else {
                        LOGGER.warning("Empty translated text for TTS: " + translatedText);
                    }
                } else {
                    LOGGER.warning("Invalid translation response for TTS: " + translatedText);
                }
            }
        } catch (Exception e) {
            LOGGER.severe("Error sending TTS for roomId " + roomId + ": " + e.getMessage());
        }
    }

    // [ДОБАВЛЕНО] Метод для сохранения настроек в userSettings
    public void saveUserSettings(String key, boolean translationEnabled, String translationLanguage, boolean ttsEnabled) {
        userSettings.put("translation_enabled_" + key, String.valueOf(translationEnabled));
        userSettings.put("translation_language_" + key, translationLanguage);
        userSettings.put("tts_enabled_" + key, String.valueOf(ttsEnabled));
        LOGGER.info("Saved settings for key: " + key + ", translationEnabled: " + translationEnabled +
                ", translationLanguage: " + translationLanguage + ", ttsEnabled: " + ttsEnabled);
    }
}
