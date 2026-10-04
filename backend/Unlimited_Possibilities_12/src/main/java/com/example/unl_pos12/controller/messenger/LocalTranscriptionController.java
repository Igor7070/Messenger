package com.example.unl_pos12.controller.messenger;

import com.example.unl_pos12.service.OpenAIService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

/**
 * Готовый текст от клиента, который распознал речь у себя на устройстве.
 *
 * В бесплатном режиме телефон распознаёт собственный микрофон средствами
 * Android и присылает уже текст — звук на сервер не уходит вовсе. Серверу
 * остаётся разослать текст обоим участникам; переводит его получатель, тоже
 * у себя. Платных запросов этот путь не делает ни одного.
 */
@Controller
public class LocalTranscriptionController {

    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    private OpenAIService openAIService;

    @MessageMapping("/local-transcription/{roomId}")
    public void handleLocalTranscription(@DestinationVariable String roomId, @Payload String message) {
        try {
            JsonNode json = mapper.readTree(message);
            String sessionId = json.has("sessionId") ? json.get("sessionId").asText() : null;
            String text = json.has("text") ? json.get("text").asText() : null;
            if (sessionId == null || text == null || text.isBlank()) return;
            System.out.println("Local transcription for roomId " + roomId + ", length: " + text.length());
            openAIService.publishTranscription(roomId, sessionId, text);
        } catch (Exception e) {
            System.out.println("Error handling local transcription: " + e.getMessage());
        }
    }
}
