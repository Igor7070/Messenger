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
        handleAudioMessage(roomId, sessionId, audioData, null);
    }

    public void handleAudioMessage(String roomId, String sessionId, byte[] audioData, String spokenLanguage) {
        if (audioData == null || audioData.length == 0) {
            LOGGER.warning("Empty audio data for roomId: " + roomId);
            return;
        }
        try {
            String bufferKey = roomId + "_" + sessionId;
            VoiceSegmenter segmenter = segmenters.computeIfAbsent(bufferKey, k -> new VoiceSegmenter());
            byte[] phrase = segmenter.accept(audioData);
            cleanupIdleSegmenters();
            if (phrase == null) return; // человек ещё говорит

            long phraseMs = phrase.length / 32; // PCM 16 бит, моно, 16 кГц
            LOGGER.info("Phrase ready: roomId=" + roomId + ", sessionId=" + sessionId + ", duration=" + phraseMs + " ms");

            // Распознавание — сетевой запрос: не держим на нём поток веб-сокета,
            // иначе звук от собеседника копится и разговор отстаёт.
            transcriptionExecutor.submit(() -> {
                try {
                    byte[] wavBytes = convertToWav(phrase);
                    // Язык говорящего не меняется в середине разговора: определяем
                    // его на первой внятной фразе и дальше сообщаем Whisper явно
                    // Выбранный пользователем язык надёжнее любого автоопределения
                    String chosen = (spokenLanguage != null && !spokenLanguage.isBlank()
                            && !"auto".equalsIgnoreCase(spokenLanguage)) ? spokenLanguage : null;
                    String known = chosen != null ? chosen : sessionLanguages.get(bufferKey);
                    Transcription result = transcribeAudio(wavBytes, known);
                    if (chosen == null && known == null && result.language != null && phraseMs >= 1200
                            && result.text != null && result.text.trim().length() >= 8) {
                        String code = toIsoCode(result.language);
                        if (code != null) {
                            sessionLanguages.put(bufferKey, code);
                            LOGGER.info("Speaker language locked: roomId=" + roomId + ", language=" + code);
                        }
                    }
                    if (isValidTranscription(result.text)) {
                        sendTranscription(roomId, sessionId, result.text);
                    } else {
                        LOGGER.warning("Filtered out invalid transcription for roomId: " + roomId + ": " + result.text);
                    }
                } catch (Exception e) {
                    LOGGER.severe("Error transcribing phrase for roomId " + roomId + ": " + e.getMessage());
                }
            });
        } catch (Exception e) {
            LOGGER.severe("Error processing audio for roomId " + roomId + ": " + e.getMessage());
        }
    }

    /**
     * Разослать готовый текст участникам звонка.
     *
     * Используется бесплатным режимом: телефон распознал речь у себя, платить
     * за распознавание не нужно, переводит текст получатель — тоже у себя.
     * Поэтому озвучка на сервере здесь не запускается.
     */
    public void publishTranscription(String roomId, String sessionId, String text) {
        if (isValidTranscription(text)) {
            sendTranscription(roomId, sessionId, text, false);
        }
    }

    /** Язык каждого говорящего, определённый на первой фразе разговора. */
    private final java.util.Map<String, String> sessionLanguages = new java.util.concurrent.ConcurrentHashMap<>();

    /** Whisper возвращает название языка словом ("russian"), а принимает код ("ru"). */
    private static String toIsoCode(String whisperLanguage) {
        if (whisperLanguage == null) return null;
        String name = whisperLanguage.trim().toLowerCase();
        if (name.length() == 2) return name; // уже код
        java.util.Map<String, String> known = java.util.Map.ofEntries(
                java.util.Map.entry("russian", "ru"), java.util.Map.entry("english", "en"),
                java.util.Map.entry("ukrainian", "uk"), java.util.Map.entry("french", "fr"),
                java.util.Map.entry("german", "de"), java.util.Map.entry("spanish", "es"),
                java.util.Map.entry("italian", "it"), java.util.Map.entry("polish", "pl"),
                java.util.Map.entry("portuguese", "pt"), java.util.Map.entry("turkish", "tr"),
                java.util.Map.entry("chinese", "zh"), java.util.Map.entry("japanese", "ja"),
                java.util.Map.entry("korean", "ko"), java.util.Map.entry("arabic", "ar"),
                java.util.Map.entry("hindi", "hi"), java.util.Map.entry("dutch", "nl"),
                java.util.Map.entry("czech", "cs"), java.util.Map.entry("belarusian", "be"),
                java.util.Map.entry("kazakh", "kk"), java.util.Map.entry("hebrew", "he"));
        return known.get(name);
    }

    /** Нарезка речи на фразы — по одной на каждого говорящего в комнате. */
    private final java.util.Map<String, VoiceSegmenter> segmenters = new java.util.concurrent.ConcurrentHashMap<>();

    private final java.util.concurrent.ExecutorService transcriptionExecutor =
            java.util.concurrent.Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "transcription");
                t.setDaemon(true);
                return t;
            });

    /** Разговоры заканчиваются без уведомления — забытые буферы убираем сами. */
    private void cleanupIdleSegmenters() {
        segmenters.entrySet().removeIf(e -> {
            if (!e.getValue().isIdle(120000)) return false;
            sessionLanguages.remove(e.getKey()); // разговор закончился — язык не помним
            return true;
        });
    }

    // [ДОБАВЛЕНО] Метод для фильтрации транскрипции
    public boolean isValidTranscription(String transcription) {
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

    public byte[] convertToWav(byte[] rawAudio) throws IOException {
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

    public String transcribeAudio(byte[] wavAudio) throws IOException {
        return transcribeAudio(wavAudio, null).text;
    }

    /** Результат распознавания: текст и язык, который определил Whisper. */
    public static class Transcription {
        public final String text;
        public final String language; // код вида "russian"/"english" или null

        Transcription(String text, String language) {
            this.text = text;
            this.language = language;
        }
    }

    /**
     * Распознавание речи.
     *
     * @param language язык говорящего (ISO-639-1, например "ru"); null — пусть
     *        Whisper определит сам. Указывать важно: на коротких фразах
     *        автоопределение ошибается — английскую реплику после русского
     *        разговора модель записывала кириллицей, и переводился уже бред.
     */
    public Transcription transcribeAudio(byte[] wavAudio, String language) throws IOException {
        MultipartBody.Builder builder = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", "audio.wav",
                        RequestBody.create(wavAudio, MediaType.parse("audio/wav")))
                .addFormDataPart("model", "whisper-1")
                // verbose_json нужен, чтобы узнать определённый язык и запомнить его
                .addFormDataPart("response_format", "verbose_json");
        if (language != null && !language.isBlank()) {
            builder.addFormDataPart("language", language);
        }

        Request request = new Request.Builder()
                .url("https://api.openai.com/v1/audio/transcriptions")
                .header("Authorization", "Bearer " + apiKey)
                .post(builder.build())
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "No response body";
                LOGGER.severe("Whisper API error: " + response.code() + ", " + errorBody);
                throw new IOException("Whisper API error: " + response.code() + ", " + errorBody);
            }
            String responseBody = response.body().string();
            ObjectNode json = (ObjectNode) mapper.readTree(responseBody);
            String text = json.has("text") ? json.get("text").asText() : "";
            String detected = json.has("language") ? json.get("language").asText() : null;
            return new Transcription(text, detected);
        }
    }

    public String transcribeChatAudio(byte[] audioBytes) throws IOException {
        LOGGER.info("Transcribing chat audio, input length: " + audioBytes.length + " bytes");
        RequestBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", "audio.mp3", // Отправляем как MP3
                        RequestBody.create(audioBytes, MediaType.parse("audio/mpeg")))
                .addFormDataPart("model", "whisper-1")
                .addFormDataPart("language", "ru") // Указываем русский язык
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
            String transcription = json.get("text").asText();
            LOGGER.info("Chat transcription result: " + transcription);
            return transcription;
        }
    }

    private void sendTranscription(String roomId, String sessionId, String transcription) {
        sendTranscription(roomId, sessionId, transcription, true);
    }

    /** @param withServerTts озвучивать ли на сервере (в бесплатном режиме — нет) */
    private void sendTranscription(String roomId, String sessionId, String transcription, boolean withServerTts) {
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
                if (withServerTts) sendTTS(roomId, sessionId, recipientId, transcription);
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
    public byte[] synthesizeSpeech(String text) throws IOException {
        LOGGER.info("Synthesizing speech for text: " + text);
        if (text == null || text.trim().isEmpty()) {
            LOGGER.warning("Invalid input for TTS: text=" + text);
            throw new IOException("Invalid text for TTS");
        }

        ObjectNode ttsRequest = mapper.createObjectNode();
        ttsRequest.put("model", "gpt-4o-mini-tts");
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

    // [ДОБАВЛЕНО] Метод для сохранения настроек в userSettings...
    public void saveUserSettings(String key, boolean translationEnabled, String translationLanguage, boolean ttsEnabled) {
        userSettings.put("translation_enabled_" + key, String.valueOf(translationEnabled));
        userSettings.put("translation_language_" + key, translationLanguage);
        userSettings.put("tts_enabled_" + key, String.valueOf(ttsEnabled));
        LOGGER.info("Saved settings for key: " + key + ", translationEnabled: " + translationEnabled +
                ", translationLanguage: " + translationLanguage + ", ttsEnabled: " + ttsEnabled);

        // Сообщаем язык второй стороне: по нему собеседник заранее готовит
        // нужную пару для перевода, а не скачивает её посреди разговора
        try {
            int sep = key.lastIndexOf('_');
            if (sep > 0) {
                String roomId = key.substring(0, sep);
                String userId = key.substring(sep + 1);
                com.fasterxml.jackson.databind.node.ObjectNode msg = mapper.createObjectNode();
                msg.put("userId", userId);
                msg.put("language", translationLanguage);
                messagingTemplate.convertAndSend("/topic/call-language/" + roomId, mapper.writeValueAsString(msg));
                LOGGER.info("Broadcast call language: room=" + roomId + ", user=" + userId + ", lang=" + translationLanguage);
            }
        } catch (Exception e) {
            LOGGER.warning("Failed to broadcast call language: " + e.getMessage());
        }
    }

    /** Язык, выбранный участником звонка (для подготовки пары перевода). */
    public String getUserLanguage(String roomId, String userId) {
        return userSettings.get("translation_language_" + roomId + "_" + userId);
    }
}
