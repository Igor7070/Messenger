package com.example.unl_pos12.controller.messenger;

import com.example.unl_pos12.service.OpenAIService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

import java.util.Base64;
import java.util.logging.Logger;

@Controller
public class AudioMessageController {
    private static final Logger LOGGER = Logger.getLogger(AudioMessageController.class.getName());
    @Autowired
    private OpenAIService openAIService;

    @MessageMapping("/app/audio-transcription/{roomId}")
    public void handleAudio(@DestinationVariable String roomId, @Payload String base64Audio) {
        LOGGER.info("Received audio message for roomId " + roomId + ", Base64 length: " + base64Audio.length());
        try {
            byte[] audioData = Base64.getDecoder().decode(base64Audio);
            LOGGER.info("Decoded audio data for roomId " + roomId + ": " + audioData.length + " bytes");
            if (audioData.length > 0) {
                openAIService.handleAudioMessage(roomId, "session-" + roomId, audioData);
            } else {
                LOGGER.warning("Empty audio data for roomId " + roomId);
            }
        } catch (Exception e) {
            LOGGER.severe("Error decoding Base64 audio for roomId " + roomId + ": " + e.getMessage());
        }
    }
}
