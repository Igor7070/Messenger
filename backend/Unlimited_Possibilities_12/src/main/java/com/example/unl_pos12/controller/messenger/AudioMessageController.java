package com.example.unl_pos12.controller.messenger;

import com.example.unl_pos12.service.OpenAIService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

import java.util.Base64;

@Controller
public class AudioMessageController {
    @Autowired
    private OpenAIService openAIService;
    private final ObjectMapper mapper = new ObjectMapper();

    @MessageMapping("/audio-transcription/{roomId}")
    public void handleAudio(@DestinationVariable String roomId, @Payload String message) {
        System.out.println("Received audio message for roomId " + roomId + ", message length: " + message.length());
        try {
            // NEW: Парсим JSON
            var json = mapper.readTree(message);
            String base64Audio = json.get("audio").asText();
            String sessionId = json.get("sessionId").asText();
            byte[] audioData = Base64.getDecoder().decode(base64Audio);
            System.out.println("Decoded audio data for roomId " + roomId + ", sessionId " + sessionId + ": " + audioData.length + " bytes");
            if (audioData.length > 0) {
                openAIService.handleAudioMessage(roomId, sessionId, audioData);
            } else {
                System.out.println("Empty audio data for roomId " + roomId);
            }
        } catch (Exception e) {
            System.out.println("Error processing audio message for roomId " + roomId + ": " + e.getMessage());
        }
    }
}
