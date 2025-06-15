package com.example.unl_pos12.controller.messenger;

import com.example.unl_pos12.service.OpenAIService;
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

    @MessageMapping("/audio-transcription/{roomId}")
    public void handleAudio(@DestinationVariable String roomId, @Payload String base64Audio) {
        System.out.println("Received audio message for roomId " + roomId + ", Base64 length: " + base64Audio.length());
        try {
            byte[] audioData = Base64.getDecoder().decode(base64Audio);
            System.out.println("Decoded audio data for roomId " + roomId + ": " + audioData.length + " bytes");
            if (audioData.length > 0) {
                openAIService.handleAudioMessage(roomId, "session-" + roomId, audioData);
            } else {
                System.out.println("Empty audio data for roomId " + roomId);
            }
        } catch (Exception e) {
            System.out.println("Error decoding Base64 audio for roomId " + roomId + ": " + e.getMessage());
        }
    }
}
