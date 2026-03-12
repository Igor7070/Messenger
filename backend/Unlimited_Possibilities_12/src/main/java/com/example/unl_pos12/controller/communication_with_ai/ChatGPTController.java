package com.example.unl_pos12.controller.communication_with_ai;

import com.example.unl_pos12.model.chat_ai.GenerateMessageRequest;
import com.example.unl_pos12.model.chat_ai.MessageRequest;
import com.example.unl_pos12.model.chat_ai.SettingsRequest;
import com.example.unl_pos12.model.messenger.Message;
import com.example.unl_pos12.model.messenger.TranslationUpdateRequest;
import com.example.unl_pos12.repo.MessageRepository;
import com.example.unl_pos12.service.OpenAIService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import ws.schild.jave.MultimediaInfo;
import ws.schild.jave.MultimediaObject;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Controller
public class ChatGPTController {
    @Autowired
    private OpenAIService openAIService;
    @Autowired
    private MessageRepository messageRepository;

    @GetMapping("/communicating_with_a_advanced_ai_model")
    public String chatGPT(HttpSession session, Model model) {
        // Извлекаем историю чата из сессии или создаем новую, если ее нет
        List<String> chatMessages = (List<String>) session.getAttribute("chatMessagesGpt");
        if (chatMessages == null) {
            chatMessages = new ArrayList<>();
            session.setAttribute("chatMessagesGpt", chatMessages);
        }

        model.addAttribute("title", "Продвинутая модель");
        model.addAttribute("chatMessages", chatMessages);
        return "communication_with_ai/advancedModel";
    }

    @PostMapping("/communicating_with_a_advanced_ai_model")
    public String generateCompletion(@RequestParam(name = "prompt") String prompt,
                                     HttpSession session, Model model) {
        String result = openAIService.generateCompletion(prompt);

        // Извлекаем историю чата из сессии
        List<String> chatMessages = (List<String>) session.getAttribute("chatMessagesGpt");
        chatMessages.add(prompt);
        chatMessages.add(result);
        model.addAttribute("chatMessages", chatMessages);
        return "communication_with_ai/advancedModel";
    }

    @PostMapping("/api/communicating_with_a_advanced_ai_model")
    @ResponseBody
    public ResponseEntity<String> generateApiCompletion(@RequestBody MessageRequest request) {
        String result = openAIService.generateCompletion(request.getPrompt());
        return ResponseEntity.ok(result);
    }

    @PostMapping("/api/translate")
    @ResponseBody
    public ResponseEntity<String> translateMessage(@RequestBody MessageRequest request) {
        System.out.println("Translate message: " + request.getPrompt());
        if (request.getTargetLanguage() == null || request.getPrompt() == null) {
            return ResponseEntity.badRequest().body("Prompt and targetLanguage are required");
        }
        String prompt = String.format("Translate the following text to %s and return only the translated phrase in double quotes: \"%s\"",
                request.getTargetLanguage().equals("auto") ? "English" : request.getTargetLanguage(),
                request.getPrompt());
        String result = openAIService.generateCompletion(prompt);
        System.out.println(result);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/api/messages/{messageId}/translate")
    @ResponseBody
    public ResponseEntity<Void> saveTranslation(@PathVariable Long messageId,
                                                @RequestBody TranslationUpdateRequest request) {
        System.out.println("Method saveTranslation is working...");
        Message message = messageRepository.findById(messageId)
                .orElse(null);
        if (message == null) {
            return ResponseEntity.notFound().build();
        }
        message.setTranslatedContent(request.getTranslatedContent());
        message.setTranslationLanguage(request.getTargetLanguage());
        messageRepository.save(message);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/api/spellcheck")
    @ResponseBody
    public ResponseEntity<String> spellCheckMessage(@RequestBody MessageRequest request) {
        System.out.println("Spell check message: " + request.getPrompt());
        if (request.getPrompt() == null || request.getPrompt().trim().isEmpty()) {
            return ResponseEntity.badRequest().body("Prompt is required");
        }
        String prompt = String.format("Correct the spelling and grammar of the following text and return only the corrected text in double quotes: \"%s\"",
                request.getPrompt());
        String result = openAIService.generateCompletion(prompt);
        System.out.println("Spell checked result: " + result);
        return ResponseEntity.ok(result);
    }

    // [ДОБАВЛЕНО] Endpoint для сохранения настроек
    @PostMapping("/api/settings")
    @ResponseBody
    public ResponseEntity<Void> saveSettings(@RequestBody SettingsRequest request) {
        String roomId = request.getRoomId();
        String userId = request.getUserId();
        if (roomId == null || userId == null) {
            return ResponseEntity.badRequest().build();
        }
        openAIService.saveUserSettings(
                roomId + "_" + userId,
                request.isTranslationEnabled(),
                request.getTranslationLanguage() != null ? request.getTranslationLanguage() : "auto",
                request.isTtsEnabled()
        );
        System.out.println("Saved settings for roomId: " + roomId + ", userId: " + userId +
                ", translationEnabled: " + request.isTranslationEnabled() +
                ", translationLanguage: " + request.getTranslationLanguage() +
                ", ttsEnabled: " + request.isTtsEnabled());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/api/tts")
    @ResponseBody
    public ResponseEntity<ObjectNode> generateTTS(@RequestBody ObjectNode request) {
        ObjectMapper mapper = new ObjectMapper(); // Создаём локальный ObjectMapper
        try {
            String text = request.get("text").asText();
            String language = request.has("language") ? request.get("language").asText() : "auto";
            String sessionId = request.has("sessionId") ? request.get("sessionId").asText() : "";
            String userId = request.has("userId") ? request.get("userId").asText() : "";
            String chatId = request.has("chatId") ? request.get("chatId").asText() : "";

            System.out.println("Received TTS request: text=" + text + ", language=" + language +
                    ", sessionId=" + sessionId + ", userId=" + userId + ", chatId=" + chatId);

            if (text == null || text.trim().isEmpty()) {
                ObjectNode errorResponse = mapper.createObjectNode();
                errorResponse.put("error", "Text is required for TTS");
                return ResponseEntity.badRequest().body(errorResponse);
            }

            // Синтезируем аудио
            byte[] audioBytes = openAIService.synthesizeSpeech(text);
            String audioBase64 = java.util.Base64.getEncoder().encodeToString(audioBytes);

            // Формируем ответ
            ObjectNode response = mapper.createObjectNode();
            response.put("audio", audioBase64);
            response.put("sessionId", sessionId);

            System.out.println("Generated TTS audio: length=" + audioBytes.length + " bytes");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            System.out.println("Error generating TTS: " + e.getMessage());
            e.printStackTrace();
            ObjectNode errorResponse = mapper.createObjectNode();
            errorResponse.put("error", "Failed to generate TTS: " + e.getMessage());
            return ResponseEntity.status(500).body(errorResponse);
        }
    }

    // **ДОБАВЛЕНО**: Эндпоинт для транскрипции аудиофайла и сохранения результата
    @PostMapping("/api/transcribe/{messageId}")
    public ResponseEntity<String> transcribeAudio(@PathVariable Long messageId, @RequestBody Map<String, String> request) {
        System.out.println("Method transcribeAudio is working... messageId=" + messageId);
        String fileUrl = request.get("fileUrl");
        System.out.println("Received fileUrl: " + fileUrl);
        if (fileUrl == null || fileUrl.isEmpty()) {
            System.out.println("Error: fileUrl is null or empty");
            return ResponseEntity.badRequest().body("File URL is required");
        }
        Optional<Message> optionalMessage = messageRepository.findById(messageId);
        if (!optionalMessage.isPresent()) {
            System.out.println("Error: Message not found for messageId=" + messageId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Message not found");
        }
        try {
            // Загружаем файл из fileUrl
            String fileName = fileUrl.substring(fileUrl.lastIndexOf("/") + 1);
            File audioFile = new File("uploads/" + fileName);
            System.out.println("Constructed file path: " + audioFile.getAbsolutePath());
            if (!audioFile.exists()) {
                System.out.println("Error: Audio file not found at " + audioFile.getAbsolutePath());
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Audio file not found");
            }
            // Читаем файл в массив байтов
            byte[] audioBytes = Files.readAllBytes(audioFile.toPath());
            System.out.println("Audio file read: " + audioBytes.length + " bytes");
            // Проверка длительности аудио
            MultimediaObject multimediaObject = new MultimediaObject(audioFile);
            MultimediaInfo info = multimediaObject.getInfo();
            long durationMs = info.getDuration();
            System.out.println("Audio file duration: " + durationMs + " ms");
            if (durationMs < 1000) { // Меньше 1 секунды
                System.out.println("Audio file is too short: " + durationMs + " ms");
                return ResponseEntity.ok("");
            }
            // Транскрибируем MP3 напрямую
            String transcription = openAIService.transcribeChatAudio(audioBytes);
            System.out.println("Transcription result: " + transcription);
            // Проверяем валидность транскрипции
            if (!openAIService.isValidTranscription(transcription)) {
                System.out.println("Transcription is invalid or empty: " + transcription);
                return ResponseEntity.ok("");
            }
            // Сохраняем транскрипцию в базе
            Message message = optionalMessage.get();
            message.setTranscribedContent(transcription);
            messageRepository.save(message);
            System.out.println("Transcription saved for messageId=" + messageId);
            return ResponseEntity.ok(transcription);
        } catch (Exception e) {
            System.out.println(String.format("Error transcribing audio for messageId=%d: %s", messageId, e.getMessage()));
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error transcribing audio: " + e.getMessage());
        }
    }

    @PostMapping("/api/generate-message")
    @ResponseBody
    public ResponseEntity<String> generateAiMessage(@RequestBody GenerateMessageRequest request) {
        System.out.println("=== /api/generate-message вызван ===");
        System.out.println("Запрос получен: " + request);

        try {
            // 1. Логируем входные данные подробно
            System.out.println("История сообщений (размер): " +
                    (request.getHistory() != null ? request.getHistory().size() : 0));
            if (request.getHistory() != null && !request.getHistory().isEmpty()) {
                System.out.println("Последние 3 сообщения (для отладки):");
                int start = Math.max(0, request.getHistory().size() - 3);
                for (int i = start; i < request.getHistory().size(); i++) {
                    Message msg = request.getHistory().get(i);
                    String sender = msg.getSender() != null && msg.getSender().getUsername() != null
                            ? msg.getSender().getUsername()
                            : "Собеседник";
                    System.out.println("  " + sender + ": " + msg.getContent());
                }
            }
            System.out.println("Пожелание (preference): " +
                    (request.getPreference() != null ? request.getPreference() : "отсутствует"));

            // 2. Формируем промпт
            StringBuilder prompt = new StringBuilder();
            prompt.append("Вот чат в мессенджере и последние сообщения (от старых к новым):\n\n");

            if (request.getHistory() == null || request.getHistory().isEmpty()) {
                prompt.append("[Нет предыдущих сообщений в чате].\n");
            } else {
                for (Message msg : request.getHistory()) {
                    String sender = msg.getSender() != null && msg.getSender().getUsername() != null
                            ? msg.getSender().getUsername()
                            : "Собеседник";
                    prompt.append(sender).append(": ").append(msg.getContent()).append("\n");
                }
            }

            prompt.append("\nСформулируй максимально корректный ответ из контекста содержания сообщений собеседников, учитывая и язык сообщений. ");
            prompt.append("Обращай внимание по приоритету от последнего сообщения и выше для понимания контекста. ");

            if (request.getPreference() != null && !request.getPreference().trim().isEmpty()) {
                prompt.append("Учти пожелания для ответа: ").append(request.getPreference()).append(". ");
            }

            prompt.append("Ответ заключи в двойные кавычки.");

            // 3. Показываем полный промпт перед отправкой в OpenAI
            System.out.println("\n=== Полный промпт, отправляемый в OpenAI ===");
            System.out.println(prompt.toString());
            System.out.println("=== Конец промпта ===\n");

            // 4. Вызываем OpenAI и логируем ответ
            System.out.println("Вызываем generateCompletion...");
            String generatedText = openAIService.generateCompletion(prompt.toString());
            System.out.println("OpenAI вернул сырой ответ: " + generatedText);

            // 5. Очищаем от кавычек и лишних пробелов
            if (generatedText.startsWith("\"") && generatedText.endsWith("\"")) {
                generatedText = generatedText.substring(1, generatedText.length() - 1).trim();
            } else {
                generatedText = generatedText.trim();
            }

            System.out.println("Итоговый очищенный текст для фронта: " + generatedText);
            System.out.println("=== /api/generate-message завершён успешно ===\n");

            return ResponseEntity.ok(generatedText);

        } catch (Exception e) {
            System.err.println("=== Ошибка в /api/generate-message ===");
            System.err.println("Сообщение: " + e.getMessage());
            e.printStackTrace();
            System.err.println("=== Конец ошибки ===\n");

            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Ошибка генерации: " + e.getMessage());
        }
    }

    @PostMapping("/api/global-assistant/translate-voice")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> translateVoiceGlobalAssistant(
            @RequestParam("audio") MultipartFile audioFile,
            @RequestParam(value = "targetLanguage", required = false, defaultValue = "auto") String targetLanguage,
            @RequestParam(value = "sourceLanguage", required = false, defaultValue = "auto") String sourceLanguage,
            @RequestParam(value = "withTts", defaultValue = "true") boolean withTts) {

        System.out.println("Method translateVoiceGlobalAssistant is working...");

        try {
            if (audioFile.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Аудиофайл пустой"));
            }

            byte[] audioBytes = audioFile.getBytes();
            System.out.println("[Global Assistant Voice Translator] Получен аудиофайл: " + audioBytes.length + " байт");

            // Транскрипция
            String transcription = openAIService.transcribeChatAudio(audioBytes);

            if (transcription == null || transcription.trim().isEmpty()) {
                return ResponseEntity.ok(Map.of(
                        "original", "",
                        "translated", "",
                        "audioBase64", null,
                        "message", "Не удалось распознать речь"
                ));
            }

            // Фильтрация коротких/пустых результатов
            if (!openAIService.isValidTranscription(transcription)) {
                return ResponseEntity.ok(Map.of(
                        "original", transcription,
                        "translated", "",
                        "audioBase64", null,
                        "message", "Слишком короткий или невалидный звук"
                ));
            }

            String translated = transcription;
            String detectedLang = sourceLanguage; // пока заглушка, потом можно доработать...

            // Перевод, если выбран конкретный язык
            if (!"auto".equalsIgnoreCase(targetLanguage) && !targetLanguage.trim().isEmpty()) {
                String translatePrompt = String.format(
                        "Translate the following text to %s and return only the translated phrase in double quotes: \"%s\"",
                        targetLanguage, transcription
                );
                String rawTranslated = openAIService.generateCompletion(translatePrompt);

                if (rawTranslated != null && !rawTranslated.trim().isEmpty()) {
                    if (rawTranslated.startsWith("\"") && rawTranslated.endsWith("\"")) {
                        translated = rawTranslated.substring(1, rawTranslated.length() - 1).trim();
                    } else {
                        translated = rawTranslated.trim();
                    }
                }
            }

            // TTS
            String audioBase64 = null;
            if (withTts && !translated.trim().isEmpty()) {
                byte[] ttsBytes = openAIService.synthesizeSpeech(translated);
                audioBase64 = java.util.Base64.getEncoder().encodeToString(ttsBytes);
            }

            // Ответ
            Map<String, Object> result = Map.of(
                    "original", transcription,
                    "detectedLang", detectedLang,
                    "translated", translated,
                    "targetLang", targetLanguage,
                    "audioBase64", audioBase64,
                    "ttsEnabled", withTts
            );

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            System.err.println("[Global Assistant Voice Translator] Ошибка: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }
}
