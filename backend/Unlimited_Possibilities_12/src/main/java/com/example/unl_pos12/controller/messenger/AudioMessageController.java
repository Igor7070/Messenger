package com.example.unl_pos12.controller.messenger;

import com.example.unl_pos12.service.OpenAIService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

@Controller
public class AudioMessageController {
    @Autowired
    private OpenAIService openAIService;

    @MessageMapping("/audio-transcription/{roomId}")
    public void handleAudio(@DestinationVariable String roomId, @Payload byte[] audioData) {
        openAIService.handleAudioMessage(roomId, "session-" + roomId, audioData);
    }
}
