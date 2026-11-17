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
        handleAudioMessage(roomId, sessionId, audioData, spokenLanguage, -1);
    }

    /**
     * Принять кусок звука.
     *
     * Куски приходят по вебсокету по порядку, но разбирает их пул потоков, и к
     * этому месту они успевают перемешаться: речь склеивалась с переставленными
     * полусекундными кусками, распознавание слышало кашу и выдавало то обрывки,
     * то выдуманные титры. Поэтому клиент нумерует куски, а здесь мы
     * восстанавливаем порядок: чуть ждём отставшего и идём дальше, если он
     * так и не пришёл.
     */
    public void handleAudioMessage(String roomId, String sessionId, byte[] audioData,
                                   String spokenLanguage, int seq) {
        if (audioData == null || audioData.length == 0) {
            LOGGER.warning("Empty audio data for roomId: " + roomId);
            return;
        }
        try {
            String bufferKey = roomId + "_" + sessionId;
            VoiceSegmenter segmenter = segmenters.computeIfAbsent(bufferKey, k -> new VoiceSegmenter());
            byte[] phrase = acceptInOrder(bufferKey, segmenter, audioData, seq);
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
            sendTranscription(roomId, sessionId, text);
        }
    }

    /** Куски, пришедшие раньше времени: ждут своей очереди. */
    private static class Reorder {
        int expected = -1;
        final java.util.TreeMap<Integer, byte[]> pending = new java.util.TreeMap<>();
        long waitingSince;
        int outOfOrder;
        long lastReport;
    }

    private final java.util.Map<String, Reorder> reorders = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Отдать нарезке куски в том порядке, в каком их произнёс человек.
     *
     * @return готовая фраза или null
     */
    private byte[] acceptInOrder(String bufferKey, VoiceSegmenter segmenter, byte[] chunk, int seq) {
        Reorder order = reorders.computeIfAbsent(bufferKey, k -> new Reorder());
        synchronized (order) {
            // Клиент без нумерации (старая версия) — работаем как раньше
            if (seq < 0) return segmenter.accept(chunk);

            if (order.expected < 0) order.expected = seq;
            if (seq < order.expected) {
                // Опоздал совсем: лучше отдать не на своём месте, чем потерять
                return segmenter.accept(chunk);
            }
            if (seq != order.expected) order.outOfOrder++;
            order.pending.put(seq, chunk);
            long now = System.currentTimeMillis();
            if (order.waitingSince == 0) order.waitingSince = now;

            byte[] phrase = null;
            phrase = drain(segmenter, order, phrase);

            // Отставший так и не пришёл — не держим разговор, идём дальше
            if (!order.pending.isEmpty()
                    && (order.pending.size() >= 6 || now - order.waitingSince > 400)) {
                order.expected = order.pending.firstKey();
                phrase = drain(segmenter, order, phrase);
            }
            if (order.pending.isEmpty()) order.waitingSince = 0;

            if (order.outOfOrder > 0 && now - order.lastReport > 10000) {
                order.lastReport = now;
                LOGGER.info("Порядок кусков восстановлен " + order.outOfOrder + " раз: " + bufferKey);
                order.outOfOrder = 0;
            }
            return phrase;
        }
    }

    /** Скормить нарезке всё, что лежит подряд от ожидаемого номера. */
    private byte[] drain(VoiceSegmenter segmenter, Reorder order, byte[] phrase) {
        while (order.pending.containsKey(order.expected)) {
            byte[] next = order.pending.remove(order.expected);
            order.expected++;
            order.waitingSince = System.currentTimeMillis();
            byte[] ready = segmenter.accept(next);
            // За один вызов фраза может закончиться только одна: остальное
            // останется в нарезке до следующего куска
            if (ready != null) phrase = ready;
        }
        return phrase;
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
        reorders.keySet().removeIf(key -> !segmenters.containsKey(key));
        segmenters.entrySet().removeIf(e -> {
            if (!e.getValue().isIdle(120000)) return false;
            sessionLanguages.remove(e.getKey()); // разговор закончился — язык не помним
            return true;
        });
    }

    // [ДОБАВЛЕНО] Метод для фильтрации транскрипции
    /** Выдумки распознавания на тишине — встречаются у Whisper постоянно. */
    private static final String[] HALLUCINATIONS = {
            "редактор субтитров", "корректор", "субтитры сделал", "субтитры создавал",
            "субтитры подготовил", "продолжение следует", "спасибо за просмотр",
            "подписывайтесь на канал", "продолжение в следующей серии",
            "subtitles by", "subs by", "thanks for watching", "amara.org",
            "subtitled by", "transcription by"
    };

    /**
     * Фразы, которые в разговоре звучат только от распознавания.
     *
     * Короткие и сами по себе безобидные («подпишись», «спасибо за просмотр»),
     * поэтому сверяем их целиком: внутри живой фразы такое слово оставляем.
     */
    private static final java.util.Set<String> JUNK_ALONE = java.util.Set.of(
            "подпишись", "подпишитесь", "подписывайтесь", "субтитры", "спасибо за внимание",
            "продолжение следует", "конец", "субтитры и перевод",
            "subscribe", "like and subscribe", "thank you", "thanks", "bye", "the end",
            "you", "music", "applause", "аплодисменты", "музыка"
    );

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
        // Whisper на тишине и шуме выдумывает концовки субтитров: он много учился
        // на фильмах, и в паузах выдаёт «Редактор субтитров …», «Продолжение
        // следует…» и подобное. В разговоре этого быть не может.
        String lowered = transcription.toLowerCase(java.util.Locale.ROOT);
        // Фраза целиком состоит из такого слова — это пауза, а не речь
        StringBuilder letters = new StringBuilder();
        for (char c : lowered.toCharArray()) {
            letters.append(Character.isLetterOrDigit(c) ? c : ' ');
        }
        String bare = letters.toString().replaceAll(" +", " ").trim();
        // «Подпишись», «подписывайся», «подписывайтесь» — у призыва много форм,
        // и перечислять их все бессмысленно: в разговоре отдельным возгласом
        // такое слово не звучит
        boolean oneCall = bare.split(" ").length <= 2
                && (bare.startsWith("подпис") || bare.startsWith("subscribe"));
        if (oneCall || JUNK_ALONE.contains(bare)) {
            LOGGER.info("Filtered out hallucinated transcription: " + transcription);
            return false;
        }
        for (String junk : HALLUCINATIONS) {
            if (lowered.contains(junk)) {
                LOGGER.info("Filtered out hallucinated transcription: " + transcription);
                return false;
            }
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
                // Синтезируем на сервере, только если слушатель не умеет сам:
                // в браузере своей озвучки нет, а телефон в бесплатном режиме
                // произносит перевод голосом Android — тогда сервер не нужен.
                boolean recipientSpeaksLocally = Boolean.parseBoolean(
                        userSettings.getOrDefault("local_tts_" + roomId + "_" + recipientId, "false"));
                if (!recipientSpeaksLocally) sendTTS(roomId, sessionId, recipientId, transcription);
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
                // Текст переводим целиком: прежняя просьба «вернуть фразу»
                // обрезала всё после первого предложения
                String translatePrompt = String.format(
                        "Translate the text below into %s.%n"
                                + "Translate all of it, sentence by sentence, keeping the meaning and tone.%n"
                                + "Do not add, drop or explain anything. Do not wrap the answer in quotes.%n"
                                + "Reply with the translation only.%n%n%s",
                        targetLanguage, transcription
                );
                String translatedText = generateCompletion(translatePrompt);
                if (translatedText != null) {
                    translatedText = translatedText.trim();
                    // Кавычки иногда всё же приходят — снимаем, но без них
                    // перевод теперь не отбрасываем: раньше такая фраза просто
                    // не звучала, и человек слышал тишину вместо перевода
                    if (translatedText.length() > 1
                            && translatedText.startsWith("\"") && translatedText.endsWith("\"")) {
                        translatedText = translatedText.substring(1, translatedText.length() - 1).trim();
                    }
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
                    LOGGER.warning("No translation for TTS, roomId: " + roomId);
                }
            }
        } catch (Exception e) {
            LOGGER.severe("Error sending TTS for roomId " + roomId + ": " + e.getMessage());
        }
    }

    // [ДОБАВЛЕНО] Метод для сохранения настроек в userSettings...
    public void saveUserSettings(String key, boolean translationEnabled, String translationLanguage, boolean ttsEnabled) {
        saveUserSettings(key, translationEnabled, translationLanguage, ttsEnabled, false);
    }

    public void saveUserSettings(String key, boolean translationEnabled, String translationLanguage,
                                 boolean ttsEnabled, boolean localTts) {
        userSettings.put("translation_enabled_" + key, String.valueOf(translationEnabled));
        userSettings.put("translation_language_" + key, translationLanguage);
        userSettings.put("tts_enabled_" + key, String.valueOf(ttsEnabled));
        userSettings.put("local_tts_" + key, String.valueOf(localTts));
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
